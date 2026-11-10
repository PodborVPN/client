package podbor.remote

import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
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

/**
 * Браузерная сторона: тот же [RemoteBrain], что и настоящий мозг, только значения приходят кадрами. Один поток — как в
 * браузере: разбирать кадры и звать методы надо из одного диспетчера.
 */
class RemotePort(
    private val scope: CoroutineScope,
    private val send: suspend (String) -> Unit,
) : RemoteBrain {
    override val status: StateFlow<Status> get() = statusNow
    private val statusNow = MutableStateFlow(Status(SessionView.Down, board(HARDCODED_SERVICES, emptyMap())))
    override val exitOrigin: StateFlow<Map<ServiceId, ExitOrigin>> get() = originNow
    private val originNow = MutableStateFlow<Map<ServiceId, ExitOrigin>>(emptyMap())
    override val pulse: StateFlow<PulseView> get() = pulseNow
    private val pulseNow = MutableStateFlow(PulseView.Empty)

    private val ready = MutableStateFlow(false)
    private var nextId = 1L
    private val waiting = HashMap<Long, CompletableDeferred<JsonElement>>()
    private val followers = HashMap<Long, (AnalysisStep) -> Unit>()

    /** Первый снимок пришёл: экран не покажет выдуманное «выключено». */
    suspend fun awaitReady() {
        ready.first { it }
    }

    fun receive(text: String) {
        when (val frame = wire.decodeFromString(FromBrain.serializer(), text)) {
            is FromBrain.StatusNow -> {
                statusNow.value = frame.status
                ready.value = true
            }
            is FromBrain.OriginNow -> originNow.value = frame.origin
            is FromBrain.PulseNow -> pulseNow.value = frame.pulse
            is FromBrain.Step -> if (frame.id in followers) followers.getValue(frame.id)(frame.step)
            is FromBrain.Answer -> if (frame.id in waiting) waiting.getValue(frame.id).complete(frame.result)
        }
    }

    private suspend fun <A, R> call(rpc: Rpc<A, R>, arg: A, follow: (AnalysisStep) -> Unit = {}): R {
        val id = nextId++
        val answer = CompletableDeferred<JsonElement>()
        waiting[id] = answer
        followers[id] = follow
        val ask = ToBrain.Ask(id, rpc.name, wire.encodeToJsonElement(rpc.arg, arg))
        try {
            send(wire.encodeToString(ToBrain.serializer(), ask))
            return wire.decodeFromJsonElement(rpc.result, answer.await())
        } catch (e: CancellationException) {
            scope.launch { send(wire.encodeToString(ToBrain.serializer(), ToBrain.Cancel(id))) }
            throw e
        } finally {
            waiting.remove(id)
            followers.remove(id)
        }
    }

    private fun following(onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): (AnalysisStep) -> Unit = { step ->
        when (step) {
            is AnalysisStep.Dropped -> onSkipped(step.skipped)
            is AnalysisStep.Progress -> onBoard(step.board)
        }
    }

    /** На браузере нет системного «постоянного VPN»: подсказывать о нём нечего. */
    override fun alwaysOn(): AlwaysOn = AlwaysOn.On

    override suspend fun latestRelease(): Release? = call(Rpcs.LatestRelease, Unit)
    override suspend fun open(): Boolean = call(Rpcs.Open, Unit)
    override suspend fun measureLine() = call(Rpcs.MeasureLine, Unit)
    override suspend fun refreshSubscription() = call(Rpcs.RefreshSubscription, Unit)
    override suspend fun press() = call(Rpcs.Press, Unit)
    override suspend fun setExitFilter(filter: ExitFilter) = call(Rpcs.SetExitFilter, filter)
    override suspend fun history(service: ServiceId): List<HistoryEvent> = call(Rpcs.History, service)
    override suspend fun exitCountry(service: ServiceId): String? = call(Rpcs.ExitCountry, service)

    override suspend fun analyze(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard> = call(Rpcs.Analyze, AnalyzeArg(input), following(onSkipped, onBoard)).either()

    override suspend fun known(input: SubscriptionInput): Known = call(Rpcs.Known, input)

    override suspend fun reanalyze(id: String, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> =
        call(Rpcs.Reanalyze, id, following(onSkipped, onBoard)).either()

    override suspend fun select(id: String): Picked = call(Rpcs.Select, id)
    override suspend fun delete(id: String) = call(Rpcs.Delete, id)

    override fun start() {
        scope.launch { call(Rpcs.Start, Unit) }
    }

    override fun stop() {
        scope.launch { call(Rpcs.Stop, Unit) }
    }

    override suspend fun shelf(): Subscriptions = call(Rpcs.Shelf, Unit)
    override suspend fun parse(text: String): Either<InputError, SubscriptionInput> = call(Rpcs.Parse, text).either()
}
