package podbor.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import podbor.screen.InputError
import podbor.screen.SubscriptionCard
import podbor.screen.Subscriptions

/** «Добавь подписку» на телефоне: текст из буфера и QR. Что вставлено, решает [onInput]. */
@Composable
fun SubscriptionScreen(
    error: InputError?,
    onInput: (String) -> Unit,
    onQr: () -> Unit,
    onSupport: (String) -> Unit,
    subscriptions: Subscriptions = Subscriptions.None,
    nowMs: Long = 0,
    onCard: (SubscriptionCard) -> Unit = {},
) {
    val context = LocalContext.current
    SubscriptionScreen(
        error, onSupport, subscriptions, nowMs, onCard,
        hint = "ссылку на подписку или сами конфиги — скопируй и вставь, или покажи QR",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            PrimaryButton("вставить из буфера", { onInput(clipboardText(context)) }, Modifier.weight(1f))
            QuietButton("QR", onQr, Modifier.weight(0.5f).height(Space.touch))
        }
    }
}

/** Текст буфера обмена; пусто или не текст — "" (судит inputOf). Содержимое секретно — в лог не идёт. */
private fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    return (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context)?.toString().orEmpty() }.trim()
}
