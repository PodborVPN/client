package podbor.screen

import androidx.lifecycle.SavedStateHandle
import arrow.core.Either
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import podbor.core.HARDCODED_SERVICES
import podbor.core.PulseView
import podbor.core.ScanBoard
import podbor.core.ServiceId
import podbor.core.board

/** Сервис глазами экрана: значения — потоками, релиз — по запросу (счётчик вызовов, ответ подменяется). */
internal class FakePort : ServicePort {
    override val session = MutableStateFlow<SessionView>(SessionView.Down)
    override val board = MutableStateFlow(board(HARDCODED_SERVICES, emptyMap()))
    override val exitOrigin = MutableStateFlow<Map<ServiceId, ExitOrigin>>(emptyMap())
    override val pulse = MutableStateFlow(PulseView.Empty)
    var asked = 0
    var answer: suspend () -> Release? = { null }

    override suspend fun latestRelease(): Release? {
        asked++
        return answer()
    }

    var opens = 0
    var measured: suspend () -> Boolean = { true }
    override suspend fun open(): Boolean {
        opens++
        return measured()
    }

    var lines = 0
    override suspend fun measureLine() {
        lines++
    }

    var refreshes = 0
    override suspend fun refreshSubscription() {
        refreshes++
    }

    var presses = 0
    override suspend fun press() {
        presses++
    }

    var always = AlwaysOn.Unknown
    override fun alwaysOn(): AlwaysOn = always

    var histories: Map<ServiceId, List<podbor.core.HistoryEvent>> = emptyMap()
    override suspend fun history(service: ServiceId): List<podbor.core.HistoryEvent> = histories[service].orEmpty()

    var analyses = 0
    /** Что анализ сообщит о пропущенных ссылках до досок; null — пропусков нет. */
    var skipped: Skipped? = null
    var analysis: suspend (onBoard: (ScanBoard) -> Unit) -> Either<InputError, ScanBoard> = { Either.Right(SCAN) }
    override suspend fun analyze(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard> {
        analyses++
        skipped?.let(onSkipped)
        return analysis(onBoard)
    }

    val reanalyzed = mutableListOf<String>()
    override suspend fun reanalyze(id: String, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> {
        reanalyzed += id
        return analysis(onBoard)
    }

    val selected = mutableListOf<String>()
    var picked: suspend (String) -> Picked = { Picked.Ready }
    override suspend fun select(id: String): Picked {
        selected += id
        return picked(id)
    }

    val deleted = mutableListOf<String>()
    var onDelete: (String) -> Unit = {}
    override suspend fun delete(id: String) {
        deleted += id
        onDelete(id)
    }
}

internal val SCAN = ScanBoard(3, 1, 1, 0, emptyList(), emptyList(), emptyList(), finished = true)
internal val MIXED = Skipped(servers = 4, unsupported = mapOf("hysteria2" to 2, "tuic" to 1), broken = 1)
internal val NOTE = SubscriptionNote("подписка", emptyList(), Support.None, Announce.None)

internal val CARD = SubscriptionCard("a", "подписка", 3, Analyzed.Never, Term.Unknown, NOTE)

/** Полка подписок: [subs] как есть; [note] — коротко: есть одна текущая с этими сведениями, null — подписок нет. */
internal class FakeShelf(var subs: Subscriptions = Subscriptions.Chosen(CARD, emptyList())) : SubscriptionShelf {
    constructor(note: SubscriptionNote?) : this(single(note))

    var note: SubscriptionNote?
        get() = when (val s = subs) {
            is Subscriptions.Chosen -> s.current.note
            Subscriptions.None, is Subscriptions.Unchosen -> null
        }
        set(value) {
            subs = single(value)
        }

    override suspend fun read(): Subscriptions = subs
}

private fun single(note: SubscriptionNote?): Subscriptions = when (note) {
    null -> Subscriptions.None
    else -> Subscriptions.Chosen(CARD.copy(note = note), emptyList())
}

internal class FakeMemory(var release: Release? = null) : UpdateMemory {
    val remembered = mutableListOf<Release>()
    override fun known(): Release? = release
    override fun remember(release: Release) {
        remembered += release
        this.release = release
    }
}

internal const val HOUR = 3_600_000L
internal val NEWER = Release("v0.9.2", "https://example.org/podbor-0.9.2.apk")

/** ViewModel на часах теста: его область — фоновая область теста, гаснет с концом теста. */
internal fun vmTest(block: suspend TestScope.() -> Unit) = runTest { block() }

internal fun TestScope.vm(
    memory: UpdateMemory = FakeMemory(),
    selfDistributed: Boolean = true,
    now: Long = 10 * HOUR,
    saved: SavedStateHandle = SavedStateHandle(),
    shelf: SubscriptionShelf = FakeShelf(),
    clock: () -> Long = { now },
) = MainViewModel("0.9.1", selfDistributed, memory, clock, saved, shelf, backgroundScope)

/** Пока экран подписан на чип — значение живо (как collectAsStateWithLifecycle). */
internal fun TestScope.watching(vm: MainViewModel): Job = backgroundScope.launch { vm.update.collect {} }
