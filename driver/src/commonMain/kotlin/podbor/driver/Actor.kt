package podbor.driver

import arrow.core.Nel
import arrow.core.None
import arrow.core.Option
import arrow.core.Some
import arrow.core.nonEmptyListOf
import arrow.core.some
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.random.Random
import podbor.core.Deed
import podbor.core.Letter

/** Машина ядра глазами актора: с чего начать, как шагнуть по букве, когда остановиться. */
interface Machine<S> {
    /** С чего прогон начат — шапка Ленты; по ней прогон восстанавливает машину. */
    val head: Head
    fun start(first: Letter): Pair<S, List<Deed>>
    fun step(s: S, l: Letter): Pair<S, List<Deed>>
    fun done(s: S): Boolean
}

/** Итог петли: последнее состояние машины, все буквы по порядку; closed — истёк срок раньше, чем машина готова. */
data class Run<S>(val state: S, val letters: Nel<Letter>, val closed: Boolean)

/**
 * Петля актора для любой машины ядра (owned state, без общих мьютексов).
 * Буквы — строго по одной из входящего ящика; состояние — аргумент хвостовой рекурсии, не переменная.
 * Дела исполняет execute в области проб: всё, что он запустил, — дети одного Job, отменяемого
 * целиком в конце. Поздние ответы не нужны: после done ядро их всё равно не исполнит.
 * [inbox] — ящик актора; снаружи дают свой, чтобы подписать его на общую линию (LineHub) до старта. Безразмерный.
 */
suspend fun <S> act(
    first: Letter,
    machine: Machine<S>,
    timeoutMs: Long,
    execute: suspend CoroutineScope.(Deed, SendChannel<Letter>) -> Unit,
    tape: TapeSink,
    observe: (S, Nel<Letter>) -> Unit = { _, _ -> },
    inbox: Channel<Letter> = Channel(Channel.UNLIMITED),
): Run<S> = coroutineScope {
    val probes = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + probes)
    // Срок — тоже ребёнок проб: отменяется вместе с ними.
    val deadline = scope.launch { delay(timeoutMs) }
    val id = Random.nextLong()

    suspend fun record(letter: Letter, deeds: List<Deed>) {
        tape.write(rowLine(Row.Step(id, letter, deeds)))
        deeds.forEach { scope.execute(it, inbox) }
    }

    /** Следующая буква или None — срок вышел раньше. */
    suspend fun next(): Option<Letter> = select {
        inbox.onReceive { it.some() }
        deadline.onJoin { None }
    }

    suspend fun advance(run: Run<S>, letter: Letter): Run<S> {
        val (state, deeds) = machine.step(run.state, letter)
        record(letter, deeds)
        val letters = run.letters + letter
        observe(state, letters)
        return Run(state, letters, closed = false)
    }

    tape.write(rowLine(Row.Run(TAPE_FORMAT, id, machine.head)))
    val (state, deeds) = machine.start(first)
    record(first, deeds)
    observe(state, nonEmptyListOf(first))
    loop(Run(state, nonEmptyListOf(first), closed = false), machine::done, ::next, ::advance).also {
        if (it.closed) tape.write(rowLine(Row.Closed(id)))
        probes.cancel()
    }
}

/**
 * Петля актора: буква за буквой, пока машина не готова или не вышел срок ([next] — None). Верхнего уровня, а не
 * локальная в act: локальная suspend-функция в лямбде coroutineScope хвостовой рекурсией не компилируется.
 */
private tailrec suspend fun <S> loop(
    run: Run<S>,
    done: (S) -> Boolean,
    next: suspend () -> Option<Letter>,
    advance: suspend (Run<S>, Letter) -> Run<S>,
): Run<S> =
    if (done(run.state)) {
        run
    } else {
        when (val got = next()) {
            None -> run.copy(closed = true)
            is Some -> loop(advance(run, got.value), done, next, advance)
        }
    }
