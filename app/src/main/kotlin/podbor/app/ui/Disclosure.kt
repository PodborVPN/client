package podbor.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Разрешения после шторки: геолокацию — только с согласия (вышку Android отдаёт лишь с ней); уведомление «Podbor ·
 * выход» — на Android 13+ всегда, объяснения ему не нужно.
 */
fun permissionsFor(locationAllowed: Boolean, sdk: Int): List<String> =
    (if (locationAllowed) listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList()) +
        if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

/**
 * Шторка до системного запроса геолокации: что берём, зачем, чего не берём и куда уходит. «Разрешить» — акцентом,
 * «Не сейчас» — тише; смахнуть — тоже «не сейчас».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(onAllow: () -> Unit, onDecline: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAnswer(state)
    ModalBottomSheet(
        onDismissRequest = { close(onDecline) },
        sheetState = state,
        containerColor = PodborColors.Surface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Rule) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Зачем Podbor геолокация", color = PodborColors.Text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Para("Номер вышки сотовой связи и её сигнал Android отдаёт только приложениям с доступом к геолокации.")
            Para("Podbor берёт только вышку и сигнал: они видны в строке «Сеть» и помогают понять, где именно не работает. GPS и координаты Podbor не читает.")
            Para("Всё остаётся на телефоне и никуда не отправляется. В Ленту вышка попадает под псевдонимом — и только когда ты сам нажмёшь «поделиться Лентой».")
            Para("Без доступа всё работает, просто без вышки.", Muted)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { close(onAllow) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PodborColors.Accent, contentColor = PodborColors.Background),
                contentPadding = PaddingTall,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Разрешить", style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { close(onDecline) },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Rule),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted),
                contentPadding = PaddingTall,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Не сейчас", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Кнопки шторки — той же высоты, что «Досмотреть» / «Пропустить» у анализа. */
private val PaddingTall = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

@Composable
private fun Para(text: String, color: Color = PodborColors.Text) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
}
