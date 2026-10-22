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
import podbor.core.ServerId
import podbor.core.Service
import podbor.core.ServiceId

/** Куда указывают балансеры: общий выход (за GENERAL_FOLLOWS) и выходы, закреплённые находками сервисов. */
data class ExitsState(val general: ServerId?, val pinned: Map<ServiceId, ServerId>)

/**
 * Выходы балансеров: закреплённые за сервисами и общий; незакреплённые повторяют выход GENERAL_FOLLOWS (спека
 * «Правила Xray»: пока у сервиса нет рабочего сервера, его трафик идёт туда же, куда общий). Состояние меняется
 * только после того, как Xray принял цель. Трубопроводы выставляют выходы параллельно — изменения по одному.
 */
class Exits(private val api: XrayApi, private val services: Set<Service>) {
    private val lock = Mutex()
    private val current = MutableStateFlow(ExitsState(general = null, pinned = emptyMap()))
    val state: StateFlow<ExitsState> = current.asStateFlow()

    /** Закрепить [server] за [service]; находка GENERAL_FOLLOWS ведёт и общий балансер, и все незакреплённые. */
    suspend fun make(service: ServiceId, server: ServerId): Either<EngineError, Unit> = lock.withLock {
        either {
            api.setExit(balancerOf(service), server.tag).bind()
            current.update { it.copy(pinned = it.pinned + (service to server)) }
            if (service == GENERAL_FOLLOWS) {
                api.setExit(EXIT_BALANCER, server.tag).bind()
                current.update { it.copy(general = server) }
                unpinned().forEach { api.setExit(balancerOf(it), server.tag).bind() }
            }
        }
    }

    /** Куда сейчас указывает балансер сервиса: свой закреплённый выход или общий. */
    fun effective(service: ServiceId): ServerId? = state.value.let { it.pinned[service] ?: it.general }

    fun pinned(service: ServiceId): Boolean = service in state.value.pinned

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
                (general?.let { api.setExit(balancer, it.tag) } ?: api.clearExit(balancer)).bind()
            }
        }
    }

    /** Сервисы набора без своего выхода, по ServiceId. */
    private fun unpinned(): List<ServiceId> =
        services.map { it.id }.filter { it !in state.value.pinned }.sortedBy { it.id }
}
