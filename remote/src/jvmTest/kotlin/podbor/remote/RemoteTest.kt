package podbor.remote

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.core.ExitFilter
import podbor.core.HARDCODED_SERVICES
import podbor.core.HistoryEvent
import podbor.core.PulseView
import podbor.core.ScanBoard
import podbor.core.ServiceId
import podbor.core.board
import podbor.screen.AlwaysOn
import podbor.screen.ExitOrigin
import podbor.screen.InputError
import podbor.screen.Known
import podbor.screen.Picked
import podbor.screen.Release
import podbor.screen.SessionView
import podbor.screen.Skipped
import podbor.screen.Status
import podbor.screen.SubscriptionInput
import podbor.screen.Subscriptions

private val BOARD = ScanBoard(2, 1, 0, 0, emptyList(), emptyList(), emptyList(), false)
private val FINAL = BOARD.copy(done = 2, finished = true)
private val SKIPPED = Skipped(servers = 2, unsupported = mapOf("tuic" to 1), broken = 0)

private class FakeBrain : RemoteBrain {
    val statusNow = MutableStateFlow(Status(SessionView.Up, board(HARDCODED_SERVICES, emptyMap())))
    var hangAnalyze = false
    val cancelled = CompletableDeferred<Unit>()
    var started = 0
    override val status: StateFlow<Status> get() = statusNow
    override val exitOrigin = MutableStateFlow<Map<ServiceId, ExitOrigin>>(mapOf(ServiceId("tg") to ExitOrigin.Recalled))
    override val pulse = MutableStateFlow(PulseView.Empty)
    override fun alwaysOn() = AlwaysOn.On
    override suspend fun latestRelease(): Release? = null
    override suspend fun open() = true
    override suspend fun measureLine() = Unit
    override suspend fun refreshSubscription() = Unit
    override suspend fun press() = Unit
    override suspend fun setExitFilter(filter: ExitFilter) = Unit
    override suspend fun history(service: ServiceId): List<HistoryEvent> = emptyList()
    override suspend fun exitCountry(service: ServiceId): String? = "NL"
    override suspend fun analyze(input: SubscriptionInput, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> {
        onSkipped(SKIPPED)
        onBoard(BOARD)
        if (hangAnalyze) try { awaitCancellation() } finally { cancelled.complete(Unit) }
        return Either.Right(FINAL)
    }
    override suspend fun known(input: SubscriptionInput): Known = Known.New
    override suspend fun reanalyze(id: String, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> =
        Either.Left(InputError.ScanFailed)
    override suspend fun select(id: String): Picked = Picked.Ready
    override suspend fun delete(id: String) = Unit
    override fun start() { started++ }
    override fun stop() = Unit
    override suspend fun shelf(): Subscriptions = Subscriptions.None
    override suspend fun parse(text: String): Either<InputError, SubscriptionInput> =
        if (text.isBlank()) Either.Left(InputError.Empty) else Either.Right(SubscriptionInput.Text(text))
}

/** Экранная и мозговая стороны, соединённые прямо: кадры проходят через настоящую сериализацию. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.linked(brain: FakeBrain): RemotePort {
    lateinit var port: RemotePort
    val server = BrainServer(brain, backgroundScope) { port.receive(it) }
    port = RemotePort(backgroundScope) { server.receive(it) }
    server.start()
    runCurrent()
    return port
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTest : FunSpec({
    test("снимки состояния доходят сразу и по мере изменения") {
        runTest {
            val brain = FakeBrain()
            val port = linked(brain)
            port.status.value.session shouldBe SessionView.Up
            port.exitOrigin.value shouldBe mapOf(ServiceId("tg") to ExitOrigin.Recalled)
            brain.statusNow.value = brain.statusNow.value.copy(session = SessionView.Failed("нет подписки"))
            runCurrent()
            port.status.value.session shouldBe SessionView.Failed("нет подписки")
        }
    }

    test("анализ: шаги в порядке, итог значением") {
        runTest {
            val port = linked(FakeBrain())
            val skipped = mutableListOf<Skipped>()
            val boards = mutableListOf<ScanBoard>()
            val out = async { port.analyze(SubscriptionInput.Text("x"), skipped::add, boards::add) }
            runCurrent()
            out.await() shouldBe Either.Right(FINAL)
            skipped shouldBe listOf(SKIPPED)
            boards shouldBe listOf(BOARD)
        }
    }

    test("отказ анализа и разбора — значением, не исключением") {
        runTest {
            val port = linked(FakeBrain())
            val re = async { port.reanalyze("id", {}, {}) }
            val empty = async { port.parse(" ") }
            runCurrent()
            re.await() shouldBe Either.Left(InputError.ScanFailed)
            empty.await() shouldBe Either.Left(InputError.Empty)
        }
    }

    test("отмена в браузере отменяет анализ в мозге") {
        runTest {
            val brain = FakeBrain().apply { hangAnalyze = true }
            val port = linked(brain)
            val out = async { port.analyze(SubscriptionInput.Text("x"), {}, {}) }
            runCurrent()
            out.cancel()
            runCurrent()
            brain.cancelled.isCompleted shouldBe true
        }
    }

    test("старт — в мозг; пустые ответы и nullable проходят") {
        runTest {
            val brain = FakeBrain()
            val port = linked(brain)
            port.start()
            val country = async { port.exitCountry(ServiceId("tg")) }
            val release = async { port.latestRelease() }
            runCurrent()
            brain.started shouldBe 1
            country.await() shouldBe "NL"
            release.await() shouldBe null
        }
    }
})
