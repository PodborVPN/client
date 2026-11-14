package podbor.driver

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import podbor.core.ChosenOn
import podbor.core.RecoveryRoute
import podbor.core.RecoveryApplied
import podbor.core.RecoveryChange
import podbor.core.generalServiceOf
import podbor.core.Web
import podbor.core.OsNetwork
import podbor.core.ServerId
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.Tower

/** Закреплённый выход сервиса и где он подобран — одна запись: выход без своей сети не построить. */
data class Pin(val server: ServerId, val on: ChosenOn)

/** Куда ведёт балансер: выбирает Xray сам или закреплён за сервером. */
sealed interface Route {
    data object Free : Route
    data object Unknown : Route
    data class Fixed(val server: ServerId) : Route
}

/** Сервер выхода для края и письма нажатия (в Ленте «Xray выбирает сам» — нет выхода, null). */
fun Route.serverOrNull(): ServerId? = when (this) {
    Route.Free, Route.Unknown -> null
    is Route.Fixed -> server
}

/** Тег выхода для строки Ленты; Xray выбирает сам — [free]. */
fun Route.tagOr(free: String): String = when (this) {
    Route.Free, Route.Unknown -> free
    is Route.Fixed -> server.tag
}

/** Куда указывают балансеры: общий выход (за GENERAL_FOLLOWS) и выходы, закреплённые находками сервисов. */
data class ExitsState(val general: Route, val pinned: Map<ServiceId, Pin>, val overrides: Map<ServiceId, Route> = emptyMap())

fun ExitsState.effective(service: ServiceId): Route =
    if (service == Web.id) general else serviceRoute(service)

private fun ExitsState.serviceRoute(service: ServiceId): Route = when (val pin = pinned[service]) {
    null -> overrides.getOrElse(service) { general }
    else -> Route.Fixed(pin.server)
}

/**
 * Выходы балансеров: закреплённые за сервисами и общий; незакреплённые повторяют выход GENERAL_FOLLOWS (спека
 * «Правила Xray»: пока у сервиса нет рабочего сервера, его трафик идёт туда же, куда общий). Состояние меняется
 * только после того, как Xray принял цель. Трубопроводы выставляют выходы параллельно — изменения по одному.
 * [tagOf] — тег выхода сервера для балансера сервиса: в сессии с TUN — выход пары ([pairTag]), общий — всегда тег сервера.
 */
class Exits(
    private val api: XrayApi,
    private val services: Set<Service>,
    private val tagOf: (ServerId, ServiceId) -> String = { server, _ -> server.tag },
) {
    private val generalService = generalServiceOf(services)
    private val lock = Mutex()
    private val current = MutableStateFlow(ExitsState(general = Route.Free, pinned = emptyMap()))
    val state: StateFlow<ExitsState> = current.asStateFlow()

    /**
     * Закрепить [server] за [service]; находка GENERAL_FOLLOWS ведёт и общий балансер, и все незакреплённые.
     * [on] — где выход подобран; вышка не хранится — сравнению она не нужна, а в Ленте была бы треком.
     */
    suspend fun make(service: ServiceId, server: ServerId, on: ChosenOn): Either<EngineError, Unit> = lock.withLock {
        either {
            val pin = Pin(server, on.withoutTower())
            if (service == Web.id) {
                api.setExit(EXIT_BALANCER, tagOf(server, service)).bind()
                current.update { before -> before.withGeneral(server).copy(pinned = before.pinned + (service to pin)) }
            } else {
                api.setExit(balancerOf(service), tagOf(server, service)).bind()
                current.update { it.copy(pinned = it.pinned + (service to pin), overrides = it.overrides - service) }
                if (service == generalService) {
                    api.setExit(EXIT_BALANCER, server.tag).bind()
                    current.update { it.withGeneral(server) }
                }
            }
            if (service == generalService) {
                unpinned().forEach { id ->
                    api.setExit(balancerOf(id), tagOf(server, id)).bind()
                    current.update { it.copy(overrides = it.overrides - id) }
                }
            }
        }
    }

    private fun ExitsState.withGeneral(server: ServerId): ExitsState {
        val inherited = unpinned().filter { it != Web.id }.associateWith { effective(it) }
        return copy(general = Route.Fixed(server), overrides = overrides + inherited)
    }

    /** Куда сейчас указывает балансер сервиса: свой закреплённый выход или общий. */
    fun effective(service: ServiceId): Route = state.value.effective(service)

    fun recoveryRoutes(): Map<RecoveryRoute, ServerId> = buildMap {
        val general = state.value.general
        if (general is Route.Fixed) put(RecoveryRoute.General, general.server)
        services.filter { it.id != Web.id }.forEach { service ->
            val route = effective(service.id)
            if (route is Route.Fixed) put(RecoveryRoute.Service(service.id), route.server)
        }
    }

    internal suspend fun reconcile(servers: List<ServerId>, on: ChosenOn, currentAttempt: () -> Boolean) = lock.withLock {
        val snapshot = state.value
        val routes = buildList {
            if (snapshot.general == Route.Unknown) add(RecoveryRoute.General)
            snapshot.overrides.forEach { (service, route) ->
                if (route == Route.Unknown) add(RecoveryRoute.Service(service))
            }
        }
        for (route in routes) {
            if (!currentAttempt()) break
            val targets = servers.associateBy { recoveryTarget(route, it).second }
            val balancer = when (route) {
                RecoveryRoute.General -> EXIT_BALANCER
                is RecoveryRoute.Service -> balancerOf(route.id)
            }
            api.exitTarget(balancer).onRight { target ->
                val found = targets[target]
                if (found != null && currentAttempt()) publishRecovery(route, found, on, RecoveryApplied.Applied)
            }
        }
    }

    suspend fun recover(
        changes: Map<RecoveryRoute, RecoveryChange>,
        on: ChosenOn,
        onResult: (RecoveryRoute, RecoveryApplied) -> Unit = { _, _ -> },
        currentAttempt: () -> Boolean,
    ): Map<RecoveryRoute, RecoveryApplied> = lock.withLock {
        val snapshot = recoveryRoutes()
        val results = mutableMapOf<RecoveryRoute, RecoveryApplied>()
        changes.forEach { (route, change) ->
            coroutineContext.ensureActive()
            if (currentAttempt() && snapshot[route] == change.previous) {
                results[route] = withContext(NonCancellable) {
                    val result = if (currentAttempt()) recoverRoute(route, change, on) else RecoveryApplied.Rejected
                    onResult(route, result)
                    result
                }
            } else {
                results[route] = RecoveryApplied.Rejected
            }
        }
        results
    }

    private suspend fun recoverRoute(route: RecoveryRoute, change: RecoveryChange, on: ChosenOn): RecoveryApplied {
        val (balancer, tag) = recoveryTarget(route, change.replacement)
        val (_, previousTag) = recoveryTarget(route, change.previous)
        val outcome = api.setExit(balancer, tag).fold(
            { api.exitTarget(balancer).fold(
                { RecoveryApplied.Unknown },
                { actual -> when (actual) {
                    tag -> RecoveryApplied.Applied
                    previousTag -> RecoveryApplied.Rejected
                    else -> RecoveryApplied.Unknown
                } },
            ) },
            { RecoveryApplied.Applied },
        )
        publishRecovery(route, change.replacement, on, outcome)
        return outcome
    }

    private fun recoveryTarget(route: RecoveryRoute, server: ServerId): Pair<String, String> = when (route) {
        RecoveryRoute.General -> EXIT_BALANCER to if (services.any { it.id == Web.id }) tagOf(server, Web.id) else server.tag
        is RecoveryRoute.Service -> balancerOf(route.id) to tagOf(server, route.id)
    }

    private fun publishRecovery(route: RecoveryRoute, server: ServerId, on: ChosenOn, outcome: RecoveryApplied) {
        if (outcome == RecoveryApplied.Rejected) return
        val target = if (outcome == RecoveryApplied.Applied) Route.Fixed(server) else Route.Unknown
        when (route) {
            RecoveryRoute.General -> current.update { before ->
                val inherited = unpinned().associateWith { id -> before.overrides.getOrElse(id) { before.general } }
                val pins = if (target is Route.Fixed && generalService == Web.id) {
                    before.pinned + (Web.id to Pin(server, on.withoutTower()))
                } else before.pinned - Web.id
                before.copy(general = target, pinned = pins, overrides = before.overrides + inherited)
            }
            is RecoveryRoute.Service -> current.update { before ->
                val pins = if (target is Route.Fixed) before.pinned + (route.id to Pin(server, on.withoutTower()))
                else before.pinned - route.id
                before.copy(pinned = pins, overrides = before.overrides + (route.id to target))
            }
        }
    }

    fun pinned(service: ServiceId): Boolean = service in state.value.pinned

    /** Где подобран выход, куда сейчас указывает балансер сервиса: свой — у своего закрепления, иначе — у общего. */
    fun chosenOn(service: ServiceId): ChosenOn = state.value.let { s ->
        val own = s.pinned[service]
        val general = s.pinned[generalService]
        when {
            own != null -> own.on
            s.general is Route.Fixed && general != null -> general.on
            else -> ChosenOn.Unknown
        }
    }

    /**
     * Снять закрепление [service]: его балансер снова повторяет общий выход. Снятие GENERAL_FOLLOWS снимает и общий —
     * тогда все незакреплённые (и он сам) возвращаются к выбору балансировщика Xray.
     */
    suspend fun clear(service: ServiceId): Either<EngineError, Unit> = lock.withLock {
        either {
            val moved = if (service == generalService) {
                api.clearExit(EXIT_BALANCER).bind()
                current.update { it.copy(general = Route.Free, pinned = it.pinned - service) }
                (unpinned() + service).distinct().filterNot { it == Web.id }.sortedBy { it.id }
            } else {
                current.update { it.copy(pinned = it.pinned - service) }
                listOf(service)
            }
            val general = state.value.general
            moved.forEach { id ->
                follow(balancerOf(id), id, general).bind()
                current.update { it.copy(overrides = it.overrides - id) }
            }
        }
    }

    /** Балансер незакреплённого сервиса повторяет общий выход; общего нет — выбирает Xray. */
    private suspend fun follow(balancer: String, service: ServiceId, general: Route): Either<EngineError, Unit> = when (general) {
        Route.Free, Route.Unknown -> api.clearExit(balancer)
        is Route.Fixed -> api.setExit(balancer, tagOf(general.server, service))
    }

    /** Сервисы набора без своего выхода, по ServiceId. */
    private fun unpinned(): List<ServiceId> =
        services.map { it.id }.filter { it !in state.value.pinned }.sortedBy { it.id }
}

private fun ChosenOn.withoutTower(): ChosenOn = when (this) {
    ChosenOn.Unknown -> this
    is ChosenOn.Network -> ChosenOn.Network(os.withoutTower())
}

private fun OsNetwork.withoutTower(): OsNetwork = when (this) {
    is OsNetwork.Cellular -> copy(tower = Tower.Unknown)
    OsNetwork.Wifi, OsNetwork.None -> this
}
