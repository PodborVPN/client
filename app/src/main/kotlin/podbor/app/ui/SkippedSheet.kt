package podbor.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    ModalBottomSheet(
        onDismissRequest = { close(onDismiss) },
        sheetState = state,
        containerColor = PodborColors.Surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Rule) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(words.title, color = PodborColors.Text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            words.lines.forEach { Text(it, color = PodborColors.Accent, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            Text(words.note, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = { close(onDismiss) },
                shape = RoundedCornerShape(12.dp),
                color = PodborColors.Accent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text("Понятно", color = PodborColors.Background, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
