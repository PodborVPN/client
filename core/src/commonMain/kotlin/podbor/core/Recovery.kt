package podbor.core

import kotlinx.serialization.Serializable

@Serializable
sealed interface RecoveryRoute {
    @Serializable data object General : RecoveryRoute
    @Serializable data class Service(val id: ServiceId) : RecoveryRoute
}

/** Подтверждённый диагноз в текущей сети. */
@Serializable
sealed interface RecoveryEvidence {
    @Serializable data class PairFailed(val route: RecoveryRoute.Service, val exit: ServerId) : RecoveryEvidence
    @Serializable data class ExitFailed(val exit: ServerId) : RecoveryEvidence
}

@Serializable
enum class RecoveryLine { Reachable, Unreachable, Unknown }

/** Rejected — Xray отклонил смену; Unknown — фактическая цель не установлена. */
@Serializable
enum class RecoveryApplied { Applied, Rejected, Unknown }

@Serializable
data class RecoveryChange(val previous: ServerId, val replacement: ServerId)

sealed interface RecoveryState {
    data object Watching : RecoveryState
    data class Checking(val attempt: Long, val routes: Map<RecoveryRoute, ServerId>, val evidence: RecoveryEvidence) : RecoveryState
    data class Searching(val attempt: Long, val routes: Map<RecoveryRoute, ServerId>, val evidence: RecoveryEvidence) : RecoveryState
    data class Applying(val attempt: Long, val changes: Map<RecoveryRoute, RecoveryChange>) : RecoveryState
    data class Settled(val attempt: Long, val results: Map<RecoveryRoute, RecoveryApplied>) : RecoveryState
}

@Serializable
sealed interface RecoveryLetter {
    /** Номер назначается заново для каждого наблюдения в сессии. */
    @Serializable data class Observed(
        val attempt: Long,
        val evidence: RecoveryEvidence,
        val routes: Map<RecoveryRoute, ServerId>,
    ) : RecoveryLetter
    @Serializable data class LineChecked(val attempt: Long, val line: RecoveryLine) : RecoveryLetter
    @Serializable data class Candidates(val attempt: Long, val verified: Map<RecoveryRoute, ServerId>) : RecoveryLetter
    @Serializable data class Applied(val attempt: Long, val results: Map<RecoveryRoute, RecoveryApplied>) : RecoveryLetter
    @Serializable data object Invalidated : RecoveryLetter
}

@Serializable
sealed interface RecoveryDeed {
    @Serializable data class CheckLine(val attempt: Long) : RecoveryDeed
    @Serializable data class Find(val attempt: Long, val routes: Map<RecoveryRoute, ServerId>) : RecoveryDeed
    @Serializable data class Apply(val attempt: Long, val changes: Map<RecoveryRoute, RecoveryChange>) : RecoveryDeed
    @Serializable data class Cancel(val attempt: Long) : RecoveryDeed
}

fun recoveryStep(state: RecoveryState, letter: RecoveryLetter): Pair<RecoveryState, List<RecoveryDeed>> = when (letter) {
    is RecoveryLetter.Observed -> onObservation(state, letter)
    is RecoveryLetter.LineChecked -> onLineChecked(state, letter)
    is RecoveryLetter.Candidates -> if (state is RecoveryState.Searching && state.attempt == letter.attempt) {
        candidates(state, letter.verified)
    } else idle(state)
    is RecoveryLetter.Applied -> if (state is RecoveryState.Applying && state.attempt == letter.attempt) {
        idle(RecoveryState.Settled(state.attempt, state.changes.mapValues { (route, _) ->
            letter.results.getOrElse(route) { RecoveryApplied.Unknown }
        }))
    } else idle(state)
    RecoveryLetter.Invalidated -> invalidate(state)
}

private fun onObservation(state: RecoveryState, letter: RecoveryLetter.Observed) = when (state) {
    RecoveryState.Watching -> observed(letter)
    is RecoveryState.Settled -> if (letter.attempt > state.attempt) observed(letter) else idle(state)
    is RecoveryState.Checking, is RecoveryState.Searching, is RecoveryState.Applying -> idle(state)
}

private fun onLineChecked(
    state: RecoveryState,
    letter: RecoveryLetter.LineChecked,
): Pair<RecoveryState, List<RecoveryDeed>> {
    if (state !is RecoveryState.Checking || state.attempt != letter.attempt) return idle(state)
    return when (letter.line) {
        RecoveryLine.Reachable -> RecoveryState.Searching(state.attempt, state.routes, state.evidence) to
            listOf(RecoveryDeed.Find(state.attempt, state.routes))
        RecoveryLine.Unreachable, RecoveryLine.Unknown -> idle(RecoveryState.Watching)
    }
}

private fun observed(letter: RecoveryLetter.Observed): Pair<RecoveryState, List<RecoveryDeed>> {
    val routes = when (val evidence = letter.evidence) {
        is RecoveryEvidence.PairFailed -> letter.routes.filter { (route, exit) ->
            route == evidence.route && exit == evidence.exit
        }
        is RecoveryEvidence.ExitFailed -> letter.routes.filterValues { it == evidence.exit }
    }
    if (routes.isEmpty()) return idle(RecoveryState.Watching)
    return RecoveryState.Checking(letter.attempt, routes, letter.evidence) to listOf(RecoveryDeed.CheckLine(letter.attempt))
}

private fun candidates(
    state: RecoveryState.Searching,
    verified: Map<RecoveryRoute, ServerId>,
): Pair<RecoveryState, List<RecoveryDeed>> {
    val changes = buildMap {
        state.routes.forEach { (route, previous) ->
            val replacement = verified.getOrElse(route) { previous }
            if (replacement != previous) put(route, RecoveryChange(previous, replacement))
        }
    }
    if (changes.isEmpty()) return idle(RecoveryState.Settled(state.attempt, emptyMap()))
    return RecoveryState.Applying(state.attempt, changes) to listOf(RecoveryDeed.Apply(state.attempt, changes))
}

private fun idle(state: RecoveryState): Pair<RecoveryState, List<RecoveryDeed>> = state to emptyList()

private fun invalidate(state: RecoveryState) = when (state) {
    RecoveryState.Watching, is RecoveryState.Settled -> idle(RecoveryState.Watching)
    is RecoveryState.Checking -> cancel(state.attempt)
    is RecoveryState.Searching -> cancel(state.attempt)
    is RecoveryState.Applying -> cancel(state.attempt)
}

private fun cancel(attempt: Long): Pair<RecoveryState, List<RecoveryDeed>> =
    RecoveryState.Watching to listOf(RecoveryDeed.Cancel(attempt))
