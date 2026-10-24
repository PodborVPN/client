package podbor.driver

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import podbor.core.GENERAL_FOLLOWS
import podbor.core.OsNetwork
import podbor.core.ServerId
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.Tower

/** Куда указывают балансеры: общий выход (за GENERAL_FOLLOWS) и выходы, закреплённые находками сервисов. */
data class ExitsState(val general: ServerId?, val pinned: Map<ServiceId, ServerId>)

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
    private val lock = Mutex()
    private val current = MutableStateFlow(ExitsState(general = null, pinned = emptyMap()))
    val state: StateFlow<ExitsState> = current.asStateFlow()

    /** На какой сети подобран закреплённый выход сервиса; вышки нет — она не нужна сравнению и не трек. */
    private val chosen = MutableStateFlow<Map<ServiceId, OsNetwork>>(emptyMap())

    /**
     * Закрепить [server] за [service]; находка GENERAL_FOLLOWS ведёт и общий балансер, и все незакреплённые.
     * [on] — сеть, на которой выход подобран; `null` — неизвестно.
     */
    suspend fun make(service: ServiceId, server: ServerId, on: OsNetwork? = null): Either<EngineError, Unit> = lock.withLock {
        either {
            api.setExit(balancerOf(service), tagOf(server, service)).bind()
            current.update { it.copy(pinned = it.pinned + (service to server)) }
            chosen.update { if (on == null) it - service else it + (service to on.withoutTower()) }
            if (service == GENERAL_FOLLOWS) {
                api.setExit(EXIT_BALANCER, server.tag).bind()
                current.update { it.copy(general = server) }
                unpinned().forEach { api.setExit(balancerOf(it), tagOf(server, it)).bind() }
            }
        }
    }

    /** Куда сейчас указывает балансер сервиса: свой закреплённый выход или общий. */
    fun effective(service: ServiceId): ServerId? = state.value.let { it.pinned[service] ?: it.general }

    fun pinned(service: ServiceId): Boolean = service in state.value.pinned

    /** На какой сети подобран выход, куда сейчас указывает балансер сервиса; `null` — неизвестно или выхода нет. */
    fun chosenOn(service: ServiceId): OsNetwork? = when {
        pinned(service) -> chosen.value[service]
        state.value.general != null -> chosen.value[GENERAL_FOLLOWS]
        else -> null
    }

    /**
     * Снять закрепление [service]: его балансер снова повторяет общий выход. Снятие GENERAL_FOLLOWS снимает и общий —
     * тогда все незакреплённые (и он сам) возвращаются к выбору балансировщика Xray.
     */
    suspend fun clear(service: ServiceId): Either<EngineError, Unit> = lock.withLock {
        either {
            current.update { it.copy(pinned = it.pinned - service) }
            chosen.update { it - service }
            val moved = if (service == GENERAL_FOLLOWS) {
                api.clearExit(EXIT_BALANCER).bind()
                current.update { it.copy(general = null) }
                (unpinned() + service).distinct().sortedBy { it.id }
            } else {
                listOf(service)
            }
            val general = state.value.general
            moved.forEach { id ->
                val balancer = balancerOf(id)
                (general?.let { api.setExit(balancer, tagOf(it, id)) } ?: api.clearExit(balancer)).bind()
            }
        }
    }

    /** Сервисы набора без своего выхода, по ServiceId. */
    private fun unpinned(): List<ServiceId> =
        services.map { it.id }.filter { it !in state.value.pinned }.sortedBy { it.id }
}

private fun OsNetwork.withoutTower(): OsNetwork = when (this) {
    is OsNetwork.Cellular -> copy(tower = Tower.Unknown)
    OsNetwork.Wifi, OsNetwork.None -> this
}
