package podbor.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import podbor.core.Stopwatch
import podbor.core.elapsedMs
import kotlin.time.Clock

/** Часы отрисовки — те же, что ставят время письмам (стена, мс); в UI-тестах — поддельные. */
val LocalNowMs = staticCompositionLocalOf<() -> Long> { { Clock.System.now().toEpochMilliseconds() } }

/** Шаг тиканья: десятые на карточке меняются каждые 100 мс. */
private const val TICK_MS = 100L

/** Прошедшее время секундомера; идущий перечитывает часы каждые [TICK_MS], замерший — из истории, без тиканья. */
@Composable
fun elapsedOf(watch: Stopwatch): Long? {
    val clock = LocalNowMs.current
    var now by remember { mutableLongStateOf(clock()) }
    when (watch) {
        Stopwatch.None, is Stopwatch.Stopped -> Unit
        is Stopwatch.Running -> LaunchedEffect(watch) {
            while (true) {
                now = clock()
                delay(TICK_MS)
            }
        }
    }
    return watch.elapsedMs(now)
}
