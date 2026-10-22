package podbor.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Ответ шторки: убрать её и сообщить ответ — один раз. Пока шторка уезжает, вторая кнопка, «назад» и смахивание ещё
 * доступны — повтор молчит. Ответ — когда шторка уехала или её анимацию прервал жест (экран жив — ответ в силе); экран
 * разобран посреди анимации (смена темы, шрифта, окна — уже после onDestroy) — область отменена: ответа не было,
 * пересозданный экран спросит снова.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberSheetAnswer(state: SheetState): (then: () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    var answered by remember { mutableStateOf(false) }
    return { then ->
        if (!answered) {
            answered = true
            scope.launch { state.hide() }.invokeOnCompletion { if (scope.isActive) then() }
        }
    }
}
