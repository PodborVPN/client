package podbor.remote

import arrow.core.Either
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import podbor.core.ExitFilter
import podbor.core.HistoryEvent
import podbor.core.PulseView
import podbor.core.ScanBoard
import podbor.core.ServiceId
import podbor.screen.ExitOrigin
import podbor.screen.InputError
import podbor.screen.Known
import podbor.screen.Picked
import podbor.screen.Release
import podbor.screen.ServicePort
import podbor.screen.Skipped
import podbor.screen.Status
import podbor.screen.SubscriptionInput
import podbor.screen.Subscriptions

/** Мозг глазами экрана за границей процесса: [ServicePort] и то, что на Android делает сама платформа. */
interface RemoteBrain : ServicePort {
    fun start()
    fun stop()
    suspend fun shelf(): Subscriptions
    suspend fun parse(text: String): Either<InputError, SubscriptionInput>
}

/** Ход анализа подписки: что не стало серверами и очередная доска. */
@Serializable
sealed interface AnalysisStep {
    @Serializable
    data class Dropped(val skipped: Skipped) : AnalysisStep

    @Serializable
    data class Progress(val board: ScanBoard) : AnalysisStep
}

/** Итог анализа и разбора вставленного: значением, а не исключением за границей процесса. */
@Serializable
sealed interface Outcome<out T> {
    @Serializable
    data class Failed(val error: InputError) : Outcome<Nothing>

    @Serializable
    data class Done<T>(val value: T) : Outcome<T>
}

internal fun <T> Either<InputError, T>.outcome(): Outcome<T> = fold({ Outcome.Failed(it) }, { Outcome.Done(it) })

internal fun <T> Outcome<T>.either(): Either<InputError, T> = when (this) {
    is Outcome.Failed -> Either.Left(error)
    is Outcome.Done -> Either.Right(value)
}

@Serializable
data class AnalyzeArg(val input: SubscriptionInput)

/** Метод за границей процесса: имя и как читать его аргумент и ответ. */
class Rpc<A, R>(val name: String, val arg: KSerializer<A>, val result: KSerializer<R>)

private fun <R> noArg(name: String, result: KSerializer<R>) = Rpc(name, Unit.serializer(), result)

object Rpcs {
    val LatestRelease = noArg("latestRelease", Release.serializer().nullable)
    val Open = noArg("open", Boolean.serializer())
    val MeasureLine = noArg("measureLine", Unit.serializer())
    val RefreshSubscription = noArg("refreshSubscription", Unit.serializer())
    val Press = noArg("press", Unit.serializer())
    val SetExitFilter = Rpc("setExitFilter", ExitFilter.serializer(), Unit.serializer())
    val History = Rpc("history", ServiceId.serializer(), ListSerializer(HistoryEvent.serializer()))
    val ExitCountry = Rpc("exitCountry", ServiceId.serializer(), String.serializer().nullable)
    val Analyze = Rpc("analyze", AnalyzeArg.serializer(), Outcome.serializer(ScanBoard.serializer()))
    val Known = Rpc("known", SubscriptionInput.serializer(), podbor.screen.Known.serializer())
    val Reanalyze = Rpc("reanalyze", String.serializer(), Outcome.serializer(ScanBoard.serializer()))
    val Select = Rpc("select", String.serializer(), Picked.serializer())
    val Delete = Rpc("delete", String.serializer(), Unit.serializer())
    val Start = noArg("start", Unit.serializer())
    val Stop = noArg("stop", Unit.serializer())
    val Shelf = noArg("shelf", Subscriptions.serializer())
    val Parse = Rpc("parse", String.serializer(), Outcome.serializer(SubscriptionInput.serializer()))
}

/** Что браузер шлёт мозгу. */
@Serializable
sealed interface ToBrain {
    @Serializable
    data class Ask(val id: Long, val method: String, val arg: JsonElement) : ToBrain

    @Serializable
    data class Cancel(val id: Long) : ToBrain
}

/** Что мозг шлёт браузеру: снимки по мере изменения и ответы на вопросы. */
@Serializable
sealed interface FromBrain {
    @Serializable
    data class StatusNow(val status: Status) : FromBrain

    @Serializable
    data class OriginNow(val origin: Map<ServiceId, ExitOrigin>) : FromBrain

    @Serializable
    data class PulseNow(val pulse: PulseView) : FromBrain

    @Serializable
    data class Step(val id: Long, val step: AnalysisStep) : FromBrain

    @Serializable
    data class Answer(val id: Long, val result: JsonElement) : FromBrain
}

/** Формат кадров: неизвестные поля другой версии не роняют разговор. */
internal val wire = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
