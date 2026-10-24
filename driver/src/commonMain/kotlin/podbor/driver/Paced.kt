package podbor.driver

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Кадр экрана (~60 Гц): чаще доску в UI слать незачем. */
const val FRAME_MS = 16L

/**
 * [block] отдаёт значения в `emit` сколько угодно часто (из любого потока); [sink] получает их не чаще раза в
 * [periodMs]: промежуточные пропускаются, последнее — всегда (по концу блока — сразу, если ещё не отдано).
 * Отмена блока — без досылки: экрану, который отменил, доска не нужна.
 */
suspend fun <T : Any, R> paced(periodMs: Long, sink: (T) -> Unit, block: suspend (emit: (T) -> Unit) -> R): R = coroutineScope {
    val latest = MutableStateFlow<T?>(null)
    // Пишет только насос; читается после его join — видимость даёт join.
    var given: T? = null
    val pump = launch {
        latest.filterNotNull().collect { v ->
            given = v
            sink(v)
            delay(periodMs)
        }
    }
    val out = block { latest.value = it }
    pump.cancelAndJoin()
    val last = latest.value
    if (last != null && last != given) sink(last)
    out
}
