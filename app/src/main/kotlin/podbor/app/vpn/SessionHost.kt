package podbor.app.vpn

import arrow.core.Either
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import podbor.driver.EngineError
import java.util.concurrent.atomic.AtomicInteger

/** Сессия VPN глазами снаружи: поднимается, поднята, не поднялась или её нет. */
sealed interface SessionState<out L> {
    data object Down : SessionState<Nothing>
    data object Starting : SessionState<Nothing>

    /** [scope] — область действий сессии: её отмена (стоп) отменяет их раньше освобождения ресурсов. */
    class Up<L>(val live: L, val scope: CoroutineScope) : SessionState<L>
    data class Failed(val error: EngineError) : SessionState<Nothing>
}

/** Исход действия на сессии: выполнено — или сессии нет (не поднята либо остановлена посреди действия). */
sealed interface Acted<out T> {
    data class Done<T>(val value: T) : Acted<T>
    data object NoSession : Acted<Nothing>
}

/**
 * Жизнь сессий VPN без Android. Команды старт/стоп исполняются строго по очереди одной корутиной: новый
 * подъём начинается только после того, как предыдущая область освобождена (fd старого туннеля закрыт).
 * Одна сессия — одна область ресурсов из [bring]; действия ([act]) — дети сессии, стоп отменяет их до release.
 * [ended] — сессия кончилась (стоп или неудачный подъём) с номером команды Android (startId), которая её кончила:
 * сервис зовёт `stopSelf(startId)`, и Android сам не остановит сервис, если после неё пришла новая команда
 * (быстрое выкл→вкл) — отдельный флаг «последней воли» не нужен.
 */
class SessionHost<L : Any>(
    private val scope: CoroutineScope,
    private val bring: suspend ResourceScope.() -> Either<EngineError, L>,
    private val ended: (startId: Int) -> Unit,
) {
    private sealed interface Command {
        val startId: Int

        data class Start(override val startId: Int) : Command
        data class Stop(override val startId: Int) : Command
    }

    private val stateFlow = MutableStateFlow<SessionState<L>>(SessionState.Down)
    val state: StateFlow<SessionState<L>> = stateFlow.asStateFlow()

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /**
     * Сколько стопов исполнено (область прежней сессии уже освобождена). Ждать стоп по [state] нельзя: быстрый
     * стоп→старт проскакивает Down, и StateFlow его не покажет.
     */
    private val stopsFlow = MutableStateFlow(0)
    val stops: StateFlow<Int> = stopsFlow.asStateFlow()

    /** Номер последнего исполненного старта: неудачный подъём кончает сервис им (старт посреди подъёма — тот же подъём). */
    private val lastStart = AtomicInteger(0)

    // Трогает только корутина команд.
    private var job: Job? = null

    init {
        scope.launch {
            for (c in commands) {
                when (c) {
                    is Command.Start -> begin(c.startId)
                    is Command.Stop -> end(c.startId)
                }
            }
        }
    }

    /**
     * Старт: сессии нет или она не поднялась — сразу Starting, чтобы ждущий подъёма не принял прежний Down/Failed
     * за итог этого старта. Поднятую сессию не трогает (повторный старт ничего не делает).
     */
    fun start(startId: Int) {
        stateFlow.update {
            when (it) {
                SessionState.Down, is SessionState.Failed -> SessionState.Starting
                SessionState.Starting, is SessionState.Up -> it
            }
        }
        commands.trySend(Command.Start(startId))
    }

    fun stop(startId: Int) {
        commands.trySend(Command.Stop(startId))
    }

    private suspend fun begin(startId: Int) {
        lastStart.set(startId)
        if (job?.isActive == true) return
        job?.join()
        stateFlow.value = SessionState.Starting
        job = scope.launch {
            val failed = resourceScope {
                when (val r = bring()) {
                    is Either.Left -> r
                    // supervisorScope: сбой одного действия не роняет VPN; отмена сессии отменяет все действия.
                    is Either.Right -> supervisorScope {
                        stateFlow.value = SessionState.Up(r.value, this)
                        awaitCancellation()
                    }
                }
            }
            // Сюда доходит только неудачный подъём: область уже освобождена. Failed остаётся итогом.
            stateFlow.value = SessionState.Failed(failed.value)
            ended(lastStart.get())
        }
    }

    private suspend fun end(startId: Int) {
        job?.cancelAndJoin()
        job = null
        // Неудачный подъём остаётся итогом (Failed), остальное — Down.
        when (stateFlow.value) {
            is SessionState.Failed -> Unit
            SessionState.Down, SessionState.Starting, is SessionState.Up -> stateFlow.value = SessionState.Down
        }
        stopsFlow.update { it + 1 }
        // Сервис мог быть создан самим стопом — его тоже надо отпустить; старт после стопа Android сохранит сам.
        ended(startId)
    }

    /** Действие ребёнком поднятой сессии (дождавшись конца подъёма). Стоп посреди — NoSession, блок отменён. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun <T> act(block: suspend (L) -> T): Acted<T> {
        val up = when (val s = state.first { it !is SessionState.Starting }) {
            is SessionState.Up -> s
            SessionState.Down, SessionState.Starting, is SessionState.Failed -> return Acted.NoSession
        }
        val action = up.scope.async { block(up.live) }
        action.join()
        return when (val e = action.getCompletionExceptionOrNull()) {
            null -> Acted.Done(action.getCompleted())
            is CancellationException -> Acted.NoSession
            else -> throw e
        }
    }
}
