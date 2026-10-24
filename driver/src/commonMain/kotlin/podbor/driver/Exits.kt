package podbor.driver

import arrow.core.Either
import arrow.core.raise.either
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import podbor.core.ChosenOn
import podbor.core.GENERAL_FOLLOWS
import podbor.core.OsNetwork
import podbor.core.ServerId
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.Tower

/** Закреплённый выход сервиса и где он подобран — одна запись: выход без своей сети не построить. */
data class Pin(val server: ServerId, val on: ChosenOn)

/** Куда указывают балансеры: общий выход (за GENERAL_FOLLOWS) и выходы, закреплённые находками сервисов. */
data class ExitsState(val general: ServerId?, val pinned: Map<ServiceId, Pin>)

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

    /**
     * Закрепить [server] за [service]; находка GENERAL_FOLLOWS ведёт и общий балансер, и все незакреплённые.
     * [on] — где выход подобран; вышка не хранится — сравнению она не нужна, а в Ленте была бы треком.
     */
    suspend fun make(service: ServiceId, server: ServerId, on: ChosenOn): Either<EngineError, Unit> = lock.withLock {
        either {
            api.setExit(balancerOf(service), tagOf(server, service)).bind()
            current.update { it.copy(pinned = it.pinned + (service to Pin(server, on.withoutTower()))) }
            if (service == GENERAL_FOLLOWS) {
                api.setExit(EXIT_BALANCER, server.tag).bind()
                current.update { it.copy(general = server) }
                unpinned().forEach { api.setExit(balancerOf(it), tagOf(server, it)).bind() }
            }
        }
    }

    /** Куда сейчас указывает балансер сервиса: свой закреплённый выход или общий. */
    fun effective(service: ServiceId): ServerId? = state.value.let { s ->
        when (val pin = s.pinned[service]) {
            null -> s.general
            else -> pin.server
        }
    }

    fun pinned(service: ServiceId): Boolean = service in state.value.pinned

    /** Где подобран выход, куда сейчас указывает балансер сервиса: свой — у своего закрепления, иначе — у общего. */
    fun chosenOn(service: ServiceId): ChosenOn = state.value.let { s ->
        val own = s.pinned[service]
        val general = s.pinned[GENERAL_FOLLOWS]
        when {
            own != null -> own.on
            s.general != null && general != null -> general.on
            else -> ChosenOn.Unknown
        }
    }

    /**
     * Снять закрепление [service]: его балансер снова повторяет общий выход. Снятие GENERAL_FOLLOWS снимает и общий —
     * тогда все незакреплённые (и он сам) возвращаются к выбору балансировщика Xray.
     */
    suspend fun clear(service: ServiceId): Either<EngineError, Unit> = lock.withLock {
        either {
            current.update { it.copy(pinned = it.pinned - service) }
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

private fun ChosenOn.withoutTower(): ChosenOn = when (this) {
    ChosenOn.Unknown -> this
    is ChosenOn.Network -> ChosenOn.Network(os.withoutTower())
}

private fun OsNetwork.withoutTower(): OsNetwork = when (this) {
    is OsNetwork.Cellular -> copy(tower = Tower.Unknown)
    OsNetwork.Wifi, OsNetwork.None -> this
}
