package podbor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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

/**
 * Шторка-вопрос в цветах Podbor: заголовок, рассказ, [yes] — акцентом, [no] — тише; смахнуть — тоже [no]. Ответ —
 * один раз ([rememberSheetAnswer]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceSheet(
    title: String,
    yes: String,
    no: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAnswer(state)
    PodborSheet(state, onDismiss = { close(onNo) }, title = title) {
        body()
        Spacer(Modifier.height(Space.l))
        Answers(yes, no, onYes = { close(onYes) }, onNo = { close(onNo) })
    }
}

/** Ответы шторки: главная на всю ширину и под ней тихая. */
@Composable
internal fun Answers(yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    PrimaryButton(yes, onYes, Modifier.fillMaxWidth())
    Spacer(Modifier.height(Space.s))
    QuietButton(no, onNo, Modifier.fillMaxWidth(), color = Muted)
}

/** Абзац шторки. */
@Composable
internal fun Para(text: String, color: Color = PodborColors.Text) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Space.s))
}
