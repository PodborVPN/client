package podbor.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import podbor.app.R
import podbor.app.sub.SubscriptionNote
import podbor.app.vpn.ExitOrigin
import podbor.core.Board
import podbor.core.Button
import podbor.core.ServiceId

internal val Muted = Color(0xFF6F7A72)
internal val Rule = Color(0xFF222622)
internal val Idle = Color(0xFF3A403C)

internal fun colorOf(t: Tone): Color = when (t) {
    Tone.Ok -> PodborColors.Ok
    Tone.Warn -> PodborColors.Accent
    Tone.Fail -> PodborColors.Fail
    Tone.Neutral -> PodborColors.Text
    Tone.Muted -> Muted
}

/**
 * Главная страница под сетью (сеть — свой фрагмент сверху): кнопка (функтор) → текущий VPN (выход). Справа сверху «⋮» —
 * сведения о подписке [subscription], «Поддержка провайдера» и «Сменить подписку»; объявление провайдера — серой
 * строкой под VPN. Все слова о выходе — из ядра; экран раскладывает их и красит ([Look.kt]).
 */
@Composable
fun MainScreen(
    board: Board,
    link: Link,
    origin: Map<ServiceId, ExitOrigin>,
    shareNote: String?,
    subscription: SubscriptionNote?,
    onPress: () -> Unit,
    onOff: () -> Unit,
    onOn: () -> Unit,
    onRetry: () -> Unit,
    onShare: () -> Unit,
    onChangeSubscription: () -> Unit,
    onSupport: (String) -> Unit = {},
    /** «Поддержать» проект; null — кнопки нет (сборка для магазина: там донаты только через его оплату). */
    onDonate: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        // Один экран: кнопка сверху, нижняя линия — у нижнего края; середина берёт всё, что между, и прокручивается
        // только сама, если однажды не влезет (длинное «не нашёл» у двух сервисов).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            PressButton(faceOf(board, link), onPress, onOff, onOn)
            Tier("ТЕКУЩИЙ VPN")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VpnCard(rowsOf(board, link, origin), onRetry)
                subscription?.announce?.let { Announce(it) }
            }
            ShareLink(shareNote, onShare, onDonate)
        }
        Menu(subscription, onSupport, onChangeSubscription, Modifier.align(Alignment.TopEnd))
    }
}

/** Объявление провайдера (`announce`): серым под карточкой — его слова, не наши; длинное — две строки и многоточие. */
@Composable
private fun Announce(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

/**
 * «⋮» справа сверху: неброско, серым; в меню — то, что нужно редко. Сверху — сведения о подписке (не кнопка): имя и
 * строки [SubscriptionNote.lines]; дальше — поддержка провайдера, если дал ссылку, и смена подписки.
 */
@Composable
private fun Menu(subscription: SubscriptionNote?, onSupport: (String) -> Unit, onChangeSubscription: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            "⋮",
            color = Muted,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surface) {
            subscription?.takeIf { it.title != null || it.lines.isNotEmpty() }?.let { note ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    note.title?.let { Text(it, color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium) }
                    note.lines.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            subscription?.supportUrl?.let { url ->
                DropdownMenuItem(
                    text = { Text("Поддержка провайдера", color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        open = false
                        onSupport(url)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Сменить подписку", color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    open = false
                    onChangeSubscription()
                },
            )
        }
    }
}

/** Тег кнопки «поделиться Лентой» для UI-тестов. */
const val SHARE_TAG = "share"

/**
 * Нижняя линия — второстепенные кнопки Material с контуром, тише круглой: «поделиться Лентой» (баг-репорт, жучок) и
 * «Поддержать» ([onDonate], если есть). Под ними — почему Ленту не отдали (Лента пуста, нашёлся адрес), если так.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareLink(note: String?, onShare: () -> Unit, onDonate: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Узкий экран или крупный шрифт — вторая кнопка уходит на новую строку, а не обрезается.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Quiet("поделиться Лентой", R.drawable.ic_bug_report, PodborColors.Accent, onShare, Modifier.testTag(SHARE_TAG))
            onDonate?.let { Quiet("Поддержать", R.drawable.ic_favorite, PodborColors.Heart, it) }
        }
        // Появляется и уходит плавно: рывок сдвигал бы всё, что под строкой.
        AnimatedVisibility(visible = note != null) {
            Text(note.orEmpty(), color = PodborColors.Fail, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Отступы тихой кнопки — уже стандартных: две кнопки в строку на обычном телефоне. */
private val QUIET_PADDING = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)

/** Тихая кнопка нижней линии: контур, слова — текстом; цветной только значок ([tint]) — по нему и узнают кнопку. */
@Composable
private fun Quiet(text: String, @DrawableRes icon: Int, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(1.dp, Rule),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PodborColors.Text),
        contentPadding = QUIET_PADDING,
        modifier = modifier,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
internal fun Tier(label: String) {
    Text(
        label,
        color = Muted,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.14.em),
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
internal fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/**
 * Карточка текущего VPN: строка на сервис (или одна строка связи с VPN), разделены линией. Строка — ценность слева
 * направо: [✓/✗/… TG] | [имя сервера] | [ПРОТОКОЛ, транспорт]; под ней — её заметки с точкой и полоска поиска.
 * Под карточкой — «повторить», если подъём не удался.
 */
@Composable
private fun VpnCard(rows: List<ServiceRow>, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = 1.dp, color = Rule)
                ServiceLine(row)
            }
        }
    }
    if (rows.any { it.retry }) {
        // Акцентом, а не серым, как «поделиться Лентой»: сейчас это единственное, что человек может сделать.
        Text(
            "повторить",
            color = PodborColors.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(top = 6.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
}

/** Одна строка карточки: отметка с меткой сервиса, имя, протокол; ниже — заметки и полоска поиска этой строки. */
@Composable
internal fun ServiceLine(row: ServiceRow) {
    Box {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                if (row.label.isNotEmpty()) {
                    val (sign, color) = when (row.mark) {
                        Mark.Checking -> "…" to PodborColors.Accent
                        Mark.Works -> "✓" to PodborColors.Ok
                        Mark.Dead -> "✗" to PodborColors.Fail
                        null -> "–" to Muted
                    }
                    // Под меткой — секундомер подбора этого сервиса (с десятыми), если подбор был.
                    Column(Modifier.widthIn(min = 44.dp)) {
                        Text("$sign ${row.label}", color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        elapsedOf(row.stopwatch)?.let {
                            Text(cardTime(it), color = Muted, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                        }
                    }
                    VerticalDivider(color = Rule, modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp))
                }
                Text(
                    row.name,
                    color = PodborColors.Text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                row.protocol?.let { p ->
                    VerticalDivider(color = Rule, modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp))
                    Column {
                        Text("ПРОТОКОЛ", color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text(
                            p,
                            color = PodborColors.Text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp),
                        )
                    }
                }
            }
            row.notes.forEach { n ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp)) { Dot(colorOf(n.tone)) }
                    Spacer(Modifier.width(10.dp))
                    Text(n.text, color = if (n.tone == Tone.Neutral) PodborColors.Text else Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // Полоска — только во время поиска: после результата «1 из 92» читалась бы как 1 %. Лежит поверх нижнего отступа
        // строки, а не своей строкой: появляясь и уходя, не двигает то, что ниже.
        AnimatedVisibility(visible = row.searching, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            LinearProgressIndicator(
                color = PodborColors.Accent,
                trackColor = Rule,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 5.dp),
            )
        }
    }
}
