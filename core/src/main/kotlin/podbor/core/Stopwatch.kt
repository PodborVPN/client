package podbor.core

/**
 * Секундомер нажатия — производная истории, как и весь экран: старт — письмо нажатия, финиш — письмо, на котором
 * нажатие кончилось. «Сейчас» для идущего приходит снаружи ([elapsedMs]): тикает отрисовка, не ядро.
 */
sealed interface Stopwatch {
    data object None : Stopwatch
    data class Running(val startedAtMs: Long) : Stopwatch
    data class Stopped(val startedAtMs: Long, val stoppedAtMs: Long) : Stopwatch
}

/** Прошло мс к [nowMs]; часы ушли назад — 0, а не минус. Секундомера нет — null. */
fun Stopwatch.elapsedMs(nowMs: Long): Long? = when (this) {
    Stopwatch.None -> null
    is Stopwatch.Running -> (nowMs - startedAtMs).coerceAtLeast(0)
    is Stopwatch.Stopped -> stoppedAtMs - startedAtMs
}

/** Общий из частных: идёт, пока идёт хоть один (от самого раннего старта); иначе замирает на самом позднем финише. */
fun overall(watches: List<Stopwatch>): Stopwatch {
    val running = watches.filterIsInstance<Stopwatch.Running>()
    val stopped = watches.filterIsInstance<Stopwatch.Stopped>()
    val start = (running.map { it.startedAtMs } + stopped.map { it.startedAtMs }).minOrNull() ?: return Stopwatch.None
    return if (running.isNotEmpty()) Stopwatch.Running(start) else Stopwatch.Stopped(start, stopped.maxOf { it.stoppedAtMs })
}
