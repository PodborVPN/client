package podbor.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Мозговая сторона одного соединения: снимки — потоками, вопросы — по одному заданию на вопрос. Кадры уходят одной
 * очередью: шаги анализа не обгоняют друг друга и итог. Закрытие соединения — отмена [scope].
 */
class BrainServer(
    private val brain: RemoteBrain,
    private val scope: CoroutineScope,
    private val send: suspend (String) -> Unit,
) {
    private val outbox = Channel<FromBrain>(Channel.UNLIMITED)
    private val jobs = HashMap<Long, Job>()
    private val guard = Mutex()

    private fun emit(frame: FromBrain) {
        outbox.trySend(frame)
    }

    /** Снимки — сразу и по мере изменения. */
    fun start() {
        scope.launch { for (frame in outbox) send(wire.encodeToString(FromBrain.serializer(), frame)) }
        scope.launch { brain.status.collect { emit(FromBrain.StatusNow(it)) } }
        scope.launch { brain.exitOrigin.collect { emit(FromBrain.OriginNow(it)) } }
        scope.launch { brain.pulse.collect { emit(FromBrain.PulseNow(it)) } }
    }

    suspend fun receive(text: String) {
        when (val frame = wire.decodeFromString(ToBrain.serializer(), text)) {
            is ToBrain.Ask -> ask(frame)
            is ToBrain.Cancel -> guard.withLock {
                if (frame.id in jobs) jobs.getValue(frame.id).cancel()
                jobs.remove(frame.id)
            }
        }
    }

    private suspend fun ask(ask: ToBrain.Ask) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = handle(ask)
            guard.withLock { jobs.remove(ask.id) }
            emit(FromBrain.Answer(ask.id, result))
        }
        guard.withLock { jobs[ask.id] = job }
        job.start()
    }

    private suspend fun <A, R> on(rpc: Rpc<A, R>, raw: JsonElement, run: suspend (A) -> R): JsonElement =
        wire.encodeToJsonElement(rpc.result, run(wire.decodeFromJsonElement(rpc.arg, raw)))

    private suspend fun handle(ask: ToBrain.Ask): JsonElement {
        val raw = ask.arg
        val steps: (AnalysisStep) -> Unit = { emit(FromBrain.Step(ask.id, it)) }
        return when (ask.method) {
            Rpcs.LatestRelease.name -> on(Rpcs.LatestRelease, raw) { brain.latestRelease() }
            Rpcs.Open.name -> on(Rpcs.Open, raw) { brain.open() }
            Rpcs.MeasureLine.name -> on(Rpcs.MeasureLine, raw) { brain.measureLine() }
            Rpcs.RefreshSubscription.name -> on(Rpcs.RefreshSubscription, raw) { brain.refreshSubscription() }
            Rpcs.Press.name -> on(Rpcs.Press, raw) { brain.press() }
            Rpcs.SetExitFilter.name -> on(Rpcs.SetExitFilter, raw) { brain.setExitFilter(it) }
            Rpcs.History.name -> on(Rpcs.History, raw) { brain.history(it) }
            Rpcs.ExitCountry.name -> on(Rpcs.ExitCountry, raw) { brain.exitCountry(it) }
            Rpcs.Analyze.name -> on(Rpcs.Analyze, raw) { arg ->
                brain.analyze(arg.input, { steps(AnalysisStep.Dropped(it)) }, { steps(AnalysisStep.Progress(it)) }).outcome()
            }
            Rpcs.Known.name -> on(Rpcs.Known, raw) { brain.known(it) }
            Rpcs.Reanalyze.name -> on(Rpcs.Reanalyze, raw) { id ->
                brain.reanalyze(id, { steps(AnalysisStep.Dropped(it)) }, { steps(AnalysisStep.Progress(it)) }).outcome()
            }
            Rpcs.Select.name -> on(Rpcs.Select, raw) { brain.select(it) }
            Rpcs.Delete.name -> on(Rpcs.Delete, raw) { brain.delete(it) }
            Rpcs.Start.name -> on(Rpcs.Start, raw) { brain.start() }
            Rpcs.Stop.name -> on(Rpcs.Stop, raw) { brain.stop() }
            Rpcs.Shelf.name -> on(Rpcs.Shelf, raw) { brain.shelf() }
            Rpcs.Parse.name -> on(Rpcs.Parse, raw) { brain.parse(it).outcome() }
            else -> error("неизвестный метод ${ask.method}")
        }
    }
}
