package podbor.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import podbor.screen.InputError
import podbor.screen.SubscriptionCard
import podbor.screen.Subscriptions
import podbor.screen.Support
import podbor.app.sub.wordsOf

/**
 * «Добавь подписку»: откуда взять подписку, решает платформа ([source]; [hint] — подсказка к этому). Не принялась —
 * строка [error] словами; провайдер отказал по лимиту устройств и дал поддержку — кнопка к ней ([onSupport]).
 * Минималистично, как главный.
 */
@Composable
fun SubscriptionScreen(
    error: InputError?,
    onSupport: (String) -> Unit,
    subscriptions: Subscriptions,
    nowMs: Long,
    onCard: (SubscriptionCard) -> Unit,
    hint: String,
    source: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = Space.screen, vertical = Space.xl),
    ) {
        Caps("добавь подписку", MaterialTheme.typography.titleMedium, PodborColors.Accent)
        Text(
            hint,
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = Space.s, bottom = Space.xl),
        )
        source()
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
        SubscriptionList(subscriptions, nowMs, onCard, Modifier.weight(1f))
    }
}

/** Поддержка под ошибкой — только у отказа по лимиту устройств: там к провайдеру и идут. */
private fun supportOf(error: InputError?): Support = when (error) {
    is InputError.DeviceLimit -> error.support
    null, InputError.Empty, InputError.Unreadable, InputError.HappOnly, is InputError.Download, is InputError.NoServers,
    InputError.ScanFailed -> Support.None
}
