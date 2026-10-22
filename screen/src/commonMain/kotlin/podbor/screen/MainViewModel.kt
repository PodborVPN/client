package podbor.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import podbor.core.Board
import podbor.core.HARDCODED_SERVICES
import podbor.core.ServiceId
import podbor.core.board

/**
 * Главный экран без вьюх: значения, на которые подписаны вьюхи (Compose на Android, SwiftUI на iOS), и намерения
 * человека. Переживает пересоздание экрана; сервис приходит и уходит ([attach]/[detach]) — значения остаются последними.
 */
class MainViewModel(
    private val version: String,
    private val selfDistributed: Boolean,
    private val memory: UpdateMemory,
    private val nowMs: () -> Long,
    // Область — как у viewModelScope (Main, гаснет с ViewModel); тесты дают свою, на часах теста.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    /** Подключённый сервис; нет — null (ещё не привязан или ушёл после стопа). */
    val service: StateFlow<ServicePort?>
        field = MutableStateFlow<ServicePort?>(null)

    /** Факты экрана о VPN: свой последний шаг, привязка, последнее слово сессии. */
    val facts: StateFlow<LinkFacts>
        field = MutableStateFlow(LinkFacts())

    val board: StateFlow<Board> =
        service.keepLast { it.board }.stateIn(viewModelScope, SharingStarted.Eagerly, board(HARDCODED_SERVICES, emptyMap()))

    val origin: StateFlow<Map<ServiceId, ExitOrigin>> =
        service.keepLast { it.exitOrigin }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Чип «обновить до …»: сразу — по последнему известному релизу; GitHub спрашивается, только пока экран подписан
     * (виден), при подключении к сервису и каждой смене сессии (VPN поднялся — туннель для белых списков), не чаще
     * [checkDue]. Сборка не с GitHub — null.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val update: StateFlow<Offer?> =
        if (!selfDistributed) {
            MutableStateFlow(null)
        } else {
            service.keepLast { s -> s.session.map { s } }
                .transformLatest { s ->
                    val now = nowMs()
                    if (checkDue(memory.checkedAtMs(), now)) {
                        s.latestRelease()?.let { got ->
                            memory.remember(now, got)
                            emit(offerOf(version, got))
                        }
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), offerOf(version, memory.known()))
        }

    init {
        // Факты — всю жизнь экрана, и пока он в фоне: отзыв VPN из Настроек иначе никто бы не увидел.
        viewModelScope.launch { service.keepLast { it.session }.collect { s -> facts.update { it.seen(s) } } }
    }

    fun attach(port: ServicePort) {
        facts.update { it.bound() }
        service.value = port
    }

    fun detach() {
        facts.update { it.unbound() }
        service.value = null
    }

    fun consentAsked() = facts.update { it.askingConsent() }

    fun refused() = facts.update { it.refused() }

    fun issued() = facts.update { it.issued() }
}
