package podbor.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import podbor.screen.InputError
import podbor.app.sub.wordsOf

/**
 * «Добавь подписку»: две кнопки — текст из буфера (ссылка или конфиги) и QR. Что вставлено, решает [onInput] (inputOf);
 * не принялась — строка [error] словами; провайдер отказал по лимиту устройств и дал поддержку — кнопка к ней
 * ([onSupport]). Минималистично, как главный.
 */
@Composable
fun SubscriptionScreen(error: InputError?, onInput: (String) -> Unit, onQr: () -> Unit, onSupport: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            "ДОБАВЬ ПОДПИСКУ",
            color = PodborColors.Accent,
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.06.em),
        )
        Text(
            "ссылку на подписку или сами конфиги — скопируй и вставь, или покажи QR",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("вставить из буфера", Modifier.weight(1f)) { onInput(clipboardText(context)) }
            Choice("QR", Modifier.weight(0.5f), onQr)
        }
        // Появляется плавно: рывок сдвигал бы страницу.
        AnimatedVisibility(visible = error != null) {
            Column {
                Text(
                    error?.let(::wordsOf).orEmpty(),
                    color = PodborColors.Fail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                (error as? InputError.DeviceLimit)?.supportUrl?.let { url ->
                    Choice("Поддержка провайдера", Modifier.fillMaxWidth().padding(top = 12.dp)) { onSupport(url) }
                }
            }
        }
    }
}

/** Кнопка страницы: рамка, скругление как у карточек, слово по центру. */
@Composable
private fun Choice(text: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Rule),
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            Text(text, color = PodborColors.Text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Текст буфера обмена; пусто или не текст — "" (судит inputOf). Содержимое секретно — в лог не идёт. */
private fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    return (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context)?.toString().orEmpty() }.trim()
}
