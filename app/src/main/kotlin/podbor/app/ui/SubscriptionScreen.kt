package podbor.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
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
import podbor.screen.InputError
import podbor.screen.Support
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
            .padding(horizontal = Space.screen, vertical = Space.xl),
    ) {
        Caps("добавь подписку", MaterialTheme.typography.titleMedium, PodborColors.Accent)
        Text(
            "ссылку на подписку или сами конфиги — скопируй и вставь, или покажи QR",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Space.s, bottom = Space.xl),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            PrimaryButton("вставить из буфера", { onInput(clipboardText(context)) }, Modifier.weight(1f))
            QuietButton("QR", onQr, Modifier.weight(0.5f).height(Space.touch))
        }
        // Появляется плавно: рывок сдвигал бы страницу.
        AnimatedVisibility(visible = error != null) {
            Column {
                Text(
                    error?.let(::wordsOf).orEmpty(),
                    color = PodborColors.Fail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Space.l),
                )
                when (val s = supportOf(error)) {
                    is Support.Link -> QuietButton(
                        "Поддержка провайдера",
                        { onSupport(s.url) },
                        Modifier.fillMaxWidth().padding(top = Space.m),
                        color = PodborColors.Accent,
                    )
                    Support.None -> Unit
                }
            }
        }
    }
}

/** Поддержка под ошибкой — только у отказа по лимиту устройств: там к провайдеру и идут. */
private fun supportOf(error: InputError?): Support = when (error) {
    is InputError.DeviceLimit -> error.support
    null, InputError.Empty, InputError.Unreadable, InputError.HappOnly, is InputError.Download, is InputError.NoServers,
    InputError.ScanFailed -> Support.None
}

/** Текст буфера обмена; пусто или не текст — "" (судит inputOf). Содержимое секретно — в лог не идёт. */
private fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    return (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context)?.toString().orEmpty() }.trim()
}
