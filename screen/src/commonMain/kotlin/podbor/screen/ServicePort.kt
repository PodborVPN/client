package podbor.screen

import arrow.core.Either
import kotlinx.coroutines.flow.StateFlow
import podbor.core.Board
import podbor.core.HistoryEvent
import podbor.core.PulseView
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

    /** Пульс сессии: величины пар и трафик; без сессии — пусто. Подписчик есть — экран смотрит, опрос частый. */
    val pulse: StateFlow<PulseView>

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

    /** Включён ли системный постоянный VPN с блокировкой — для подсказки ([alwaysOnHintDue]). */
    fun alwaysOn(): AlwaysOn

    /** История сервиса для шторки — свёртка Ленты, свежие сверху, сколько влезает. */
    suspend fun history(service: ServiceId): List<HistoryEvent>


    /**
     * Анализ подписки: VPN гаснет на его время; часть ссылок не стала серверами — [onSkipped] до первой доски; доски по
     * ходу — в [onBoard].
     */
    suspend fun analyze(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard>

    /** Есть ли вставленное [input] уже в списке подписок. */
    suspend fun known(input: SubscriptionInput): Known

    /** Переанализ сохранённой подписки [id] — как [analyze], вход из её записи (ссылка или текст). */
    suspend fun reanalyze(id: String, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard>

    /** Выбор подписки [id]: сервис сначала обновляет её; кончилась — не выбирает. VPN на прежней гасится. */
    suspend fun select(id: String): Picked

    /** Удаление подписки [id] со всем, что знали только о её серверах; была текущей — VPN гасится. */
    suspend fun delete(id: String)
}

/** Сохранённые подписки глазами экрана: список карточек и текущая. */
fun interface SubscriptionShelf {
    suspend fun read(): Subscriptions
}

/** Память обновлялки: что ответил GitHub — чип сразу, до нового запроса. Android — SharedPreferences, iOS — UserDefaults. */
interface UpdateMemory {
    fun known(): Release?
    fun remember(release: Release)
}
