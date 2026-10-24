package podbor.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import podbor.app.sub.skippedWords
import podbor.screen.Skipped

/**
 * Шторка «что из подписки пропущено»: заголовок (сколько проверю или почему не вышло), счёт по схемам, что делать.
 * Одна кнопка — «Понятно»; смахивание и «назад» — то же.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkippedSheet(skipped: Skipped, onDismiss: () -> Unit) {
    val words = skippedWords(skipped)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAnswer(state)
    PodborSheet(state, onDismiss = { close(onDismiss) }, title = words.title) {
        words.lines.forEach { Text(it, color = PodborColors.Accent, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(Space.s))
        Text(words.note, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Space.xl))
        PrimaryButton("Понятно", { close(onDismiss) }, Modifier.fillMaxWidth())
    }
}
