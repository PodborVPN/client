package podbor.driver

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import podbor.core.ChosenOn
import podbor.core.OsNetwork
import podbor.core.RecoveryApplied
import podbor.core.RecoveryChange
import podbor.core.RecoveryEvidence
import podbor.core.RecoveryLetter
import podbor.core.RecoveryLine
import podbor.core.RecoveryRoute
import podbor.core.RecoveryState
import podbor.core.RoundKind
import podbor.core.ServerId
import podbor.core.VerdictKind
import podbor.core.kind
import podbor.core.recoveryStep
import podbor.core.roundOf
import kotlin.coroutines.coroutineContext

/** Запускается внутри области сессии после выбора нижележащей сети. */
suspend fun Driver.watchRecovery(
    warm: Warm,
    os: OsNetwork,
    observatory: OutboundObservatory,
    currentNetwork: () -> Boolean,
    measure: suspend () -> RoundKind,
    onState: (RecoveryState) -> Unit = {},
    onVerified: (Map<RecoveryRoute, ServerId>) -> Unit = {},
    runAttempt: suspend (suspend () -> Unit) -> Unit = { it() },
    report: (EngineError) -> Unit,
) {
    settleRecoveryLine(measure, currentNetwork, report)
    var attempt = 0L
    // Observatory хранит результаты до двух интервалов. После переезда даём прежним отсчётам истечь.
    delay(65_000)
    while (currentNetwork()) {
        withRecoveryLine(
            measure = measure,
            currentNetwork = currentNetwork,
            report = report,
        ) {
            observatory.read().fold(
                { report(it) },
                { observations -> runAttempt {
                    exits.reconcile(warm.servers.map { it.id }, ChosenOn.Network(os), currentNetwork)
                    recoverOnce(warm, os, ++attempt, observations, currentNetwork, onState, onVerified, report)
                } },
            )
        }
        delay(60_000)
    }
}

internal suspend fun settleRecoveryLine(
    measure: suspend () -> RoundKind,
    currentNetwork: () -> Boolean,
    report: (EngineError) -> Unit,
) {
    for (waitMs in listOf(0L, 2_000L, 4_000L, 8_000L, 16_000L)) {
        delay(waitMs)
        if (!currentNetwork()) return
        val round = Either.catch { measure() }.fold(
            { report(EngineError.Failed("проверка линии")); RoundKind.Unclear },
            { it },
        )
        if (round != RoundKind.Unclear) return
    }
}

internal suspend fun withRecoveryLine(
    measure: suspend () -> RoundKind,
    currentNetwork: () -> Boolean,
    report: (EngineError) -> Unit,
    proceed: suspend () -> Unit,
) {
    Either.catch { measure() }.fold(
        { report(EngineError.Failed("проверка линии")) },
        { round ->
            when (round) {
                RoundKind.Open, RoundKind.WhitelistLike -> if (currentNetwork()) proceed()
                RoundKind.Offline, RoundKind.Unclear -> Unit
            }
        },
    )
}

private suspend fun Driver.recoverOnce(
    warm: Warm,
    os: OsNetwork,
    attempt: Long,
    observations: Map<ServerId, ExitObservation>,
    currentNetwork: () -> Boolean,
    onState: (RecoveryState) -> Unit,
    onVerified: (Map<RecoveryRoute, ServerId>) -> Unit,
    report: (EngineError) -> Unit,
) {
    if (!currentNetwork()) return
    val routes = exits.recoveryRoutes()
    val active = routes.values.toSet()
    val dead = warm.servers.firstOrNull {
        it.id in active && observations[it.id] == ExitObservation.Failed &&
            recoveryProbe(warm, RecoveryRoute.General, it).kind() == VerdictKind.Unreachable &&
            recoveryProbe(warm, RecoveryRoute.General, it).kind() == VerdictKind.Unreachable
    }
    val evidence = if (dead != null) {
        RecoveryEvidence.ExitFailed(dead.id)
    } else {
        pairFailure(warm, routes, observations, onVerified, currentNetwork) ?: return
    }
    val confirmed = mutableMapOf<RecoveryRoute, RecoveryApplied>()
    val ports = object : RecoveryPorts {
        override suspend fun checkLine(attempt: Long) = Either.catch {
            when (roundOf(recoveryLine())) {
                RoundKind.Open, RoundKind.WhitelistLike -> RecoveryLine.Reachable
                RoundKind.Offline -> RecoveryLine.Unreachable
                RoundKind.Unclear -> RecoveryLine.Unknown
            }
        }.mapLeft { EngineError.Failed("проверка линии") }

        override suspend fun find(attempt: Long, routes: Map<RecoveryRoute, ServerId>) = Either.catch {
            buildMap {
                for ((route, previous) in routes) {
                    val replacement = warm.servers.asSequence()
                        .filter { it.id != previous }
                        .sortedBy { observations[it.id] != ExitObservation.Replied }
                        .take(3)
                        .firstOrNull { recoveryProbe(warm, route, it).kind() == VerdictKind.Fits }
                    if (replacement != null) put(route, replacement.id)
                }
            }
        }.mapLeft { EngineError.Failed("проверка замены") }

        override suspend fun apply(attempt: Long, changes: Map<RecoveryRoute, RecoveryChange>) =
            Either.Right(exits.recover(changes, ChosenOn.Network(os), { route, result -> confirmed[route] = result }, currentNetwork))

        override suspend fun cancel(attempt: Long) = Unit
    }
    var state: RecoveryState = RecoveryState.Watching
    var pending = listOf<RecoveryLetter>(RecoveryLetter.Observed(attempt, evidence, routes))
    val steps = mutableListOf<RecoveryStep>()
    val startedAt = recoveryTime()
    try {
        while (pending.isNotEmpty() && currentNetwork()) {
            coroutineContext.ensureActive()
            val letter = pending.first()
            val (next, deeds) = recoveryStep(state, letter)
            steps += RecoveryStep(letter, deeds)
            state = next
            if (state !is RecoveryState.Settled && currentNetwork()) onState(state)
            pending = pending.drop(1)
            for (deed in deeds) {
                executeRecovery(deed, ports, { report(it) }) { pending = pending + it }
            }
        }
    } finally {
        if (steps.isNotEmpty()) {
            if (state !is RecoveryState.Settled) {
                val letter = RecoveryLetter.Invalidated
                steps += RecoveryStep(letter, recoveryStep(state, letter).second)
            }
            recordRecovery(startedAt, steps, confirmed.toMap())
            if (state is RecoveryState.Settled && currentNetwork()) onState(state)
        }
    }
}

private suspend fun Driver.pairFailure(
    warm: Warm,
    routes: Map<RecoveryRoute, ServerId>,
    observations: Map<ServerId, ExitObservation>,
    onVerified: (Map<RecoveryRoute, ServerId>) -> Unit,
    currentNetwork: () -> Boolean,
): RecoveryEvidence.PairFailed? {
    for ((route, exit) in routes) {
        if (!currentNetwork()) return null
        if (observations[exit] != ExitObservation.Replied) continue
        val server = warm.servers.firstOrNull { it.id == exit } ?: continue
        val first = recoveryProbe(warm, route, server).kind()
        if (first == VerdictKind.Fits && currentNetwork()) onVerified(mapOf(route to exit))
        if (route is RecoveryRoute.Service && first == VerdictKind.ServiceDead &&
            recoveryProbe(warm, RecoveryRoute.General, server).kind() == VerdictKind.Fits &&
            recoveryProbe(warm, route, server).kind() == VerdictKind.ServiceDead
        ) return RecoveryEvidence.PairFailed(route, exit)
    }
    return null
}
