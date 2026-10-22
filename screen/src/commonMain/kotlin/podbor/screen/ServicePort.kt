package podbor.screen

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import podbor.core.Board
import podbor.core.ScanBoard
import podbor.core.ServiceId

/**
 * Что экрану нужно от мозга Podbor (сессия, подбор, замеры). На Android мозг — служба в том же процессе; на iOS — в
 * приложении или в расширении туннеля: значения — снимками, которые можно передать между процессами.
 */
interface ServicePort {
    val session: StateFlow<SessionView>

    /** Сводный экран ядра: сеть, кнопка, карточка на сервис. */
    val board: StateFlow<Board>

    /** Чьи выходы в замере открытия восстановлены из памяти сети. */
    val exitOrigin: StateFlow<Map<ServiceId, ExitOrigin>>

    /** Последний релиз на GitHub; не узнали — null. */
    suspend fun latestRelease(): Release?

    /**
     * Экран открыт: замер сети на сессии (линия — параллельно подъёму, выходы — на поднятой). `false` — замера не было
     * (сессии нет или её остановили посреди).
     */
    suspend fun open(): Boolean

    /** Плашка сети без VPN (страница подписки). */
    suspend fun measureLine()

    /** Тихое обновление подписки по ссылке. */
    suspend fun refreshSubscription()

    suspend fun press()

    /** Анализ подписки: VPN гаснет на его время; доски по ходу — в [onBoard]. */
    suspend fun analyze(input: SubscriptionInput, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard>
}

/** Сохранённая подписка глазами экрана: что о ней сказать; подписки нет — null. */
fun interface SubscriptionShelf {
    suspend fun note(): SubscriptionNote?
}

/** Память обновлялки: когда спрашивали GitHub и что он ответил. Android — SharedPreferences, iOS — UserDefaults. */
interface UpdateMemory {
    fun checkedAtMs(): Long?
    fun known(): Release?
    fun remember(atMs: Long, release: Release)
}
