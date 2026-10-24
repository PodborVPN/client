package podbor.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.Dp
import podbor.app.R
import podbor.screen.Announce
import podbor.screen.SubscriptionNote
import podbor.screen.Support
import podbor.screen.supportWhenStuck
import podbor.core.Board
import podbor.core.Button
import podbor.core.HistoryEvent
import podbor.core.Mood
import podbor.core.PulseView
import androidx.compose.runtime.LaunchedEffect
import podbor.core.ServiceId
import podbor.screen.Link
import podbor.screen.faceOf
import podbor.screen.Tone
import podbor.screen.Mark
import podbor.screen.ServiceRow
import podbor.screen.rowsOf
import podbor.screen.vpnTraffic
import podbor.screen.cardTime
import podbor.screen.ExitOrigin

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
    /** Пульс сессии: величины пар в строках сервисов. */
    pulse: PulseView = PulseView.Empty,
    /** История строки по её метке — для шторки ([ServiceSheet]); свежие сверху. */
    history: suspend (String) -> List<HistoryEvent> = { emptyList() },
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
                .padding(horizontal = Space.screen, vertical = Space.m),
        ) {
            PressButton(faceOf(board, link), onPress, onOff, onOn)
            TierWithTraffic("ТЕКУЩИЙ VPN", vpnTraffic(pulse))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VpnCard(rowsOf(board, link, origin, pulse = pulse), onRetry, history)
                when (val s = supportWhenStuck(board.button, supportOf(subscription))) {
                    is Support.Link -> StuckSupport { onSupport(s.url) }
                    Support.None -> Unit
                }
                when (val a = announceOf(subscription)) {
                    is Announce.Text -> AnnounceLine(a.text)
                    Announce.None -> Unit
                }
            }
            ShareLink(shareNote, onShare, onDonate)
        }
        Menu(subscription, onSupport, onChangeSubscription, Modifier.align(Alignment.TopEnd))
    }
}

/** Подписка ещё не прочитана — ни поддержки, ни объявления. */
private fun supportOf(note: SubscriptionNote?): Support = when (note) {
    null -> Support.None
    else -> note.support
}

private fun announceOf(note: SubscriptionNote?): Announce = when (note) {
    null -> Announce.None
    else -> note.announce
}

/**
 * Ничего не работает — путь к провайдеру прямо под словами тупика: «спроси у своего VPN-сервиса» — одной кнопкой, а не
 * через «⋮». Рамкой, не заливкой: это совет, а не главное действие экрана.
 */
@Composable
private fun StuckSupport(onClick: () -> Unit) {
    QuietButton("Написать в поддержку провайдера", onClick, Modifier.fillMaxWidth().padding(top = Space.m), color = PodborColors.Accent)
}

/** Объявление провайдера (`announce`): серым под карточкой — его слова, не наши; длинное — две строки и многоточие. */
@Composable
private fun AnnounceLine(text: String) {
    Text(
        text,
        color = Muted,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = Space.m),
    )
}

/**
 * «⋮» справа сверху: неброско, серым; в меню — то, что нужно редко. Сверху — сведения о подписке (не кнопка): имя и
 * строки [SubscriptionNote.lines]; дальше — поддержка провайдера, если дал ссылку, и смена подписки.
 */
@Composable
private fun Menu(subscription: SubscriptionNote?, onSupport: (String) -> Unit, onChangeSubscription: () -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    // Контур квадрата — по отступу экрана: касание 48dp выступает за него на 4dp с каждой стороны.
    Box(modifier.padding(end = Space.screen - Space.xs)) {
        IconSquareButton(R.drawable.ic_more, MENU_LABEL, onClick = { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = Corner,
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(Space.hairline, Rule),
        ) {
            subscription?.takeIf { it.title != null || it.lines.isNotEmpty() }?.let { note ->
                Column(Modifier.padding(horizontal = Space.m, vertical = Space.s)) {
                    note.title?.let { Text(it, color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium) }
                    note.lines.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            when (val s = supportOf(subscription)) {
                is Support.Link -> DropdownMenuItem(
                    text = { Text("Поддержка провайдера", color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        open = false
                        onSupport(s.url)
                    },
                )
                Support.None -> Unit
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

/** Подпись кнопки «⋮» для озвучки и UI-тестов. */
const val MENU_LABEL = "Меню"

/** Тег кнопки «поделиться Лентой» для UI-тестов. */
const val SHARE_TAG = "share"

/**
 * Нижняя линия — второстепенные кнопки Material с контуром, тише круглой: «поделиться Лентой» (баг-репорт, жучок) и
 * «Поддержать» ([onDonate], если есть). Под ними — почему Ленту не отдали (Лента пуста, нашёлся адрес), если так.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareLink(note: String?, onShare: () -> Unit, onDonate: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(top = Space.s), horizontalAlignment = Alignment.CenterHorizontally) {
        // Узкий экран или крупный шрифт — вторая кнопка уходит на новую строку, а не обрезается.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            QuietButton("поделиться Лентой", onShare, Modifier.testTag(SHARE_TAG), R.drawable.ic_bug_report, PodborColors.Accent)
            onDonate?.let { QuietButton("Поддержать", it, icon = R.drawable.ic_favorite, tint = PodborColors.Heart) }
        }
        // Появляется и уходит плавно: рывок сдвигал бы всё, что под строкой.
        AnimatedVisibility(visible = note != null) {
            Text(note.orEmpty(), color = PodborColors.Fail, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun Tier(label: String) {
    Text(
        label,
        color = Muted,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = TierSpacing),
        maxLines = 1,
        modifier = Modifier.padding(top = Space.m, bottom = Space.s),
    )
}

/**
 * Заголовок яруса с итогом трафика сразу за словом: серым, мелко, цифры одной ширины — не прыгают. Итог берёт всё место
 * до [trailing] (а не половину с распоркой) и обрезается, только если не влез; итога нет — место пустое.
 */
@Composable
internal fun TierWithTraffic(label: String, traffic: String?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Tier(label)
        if (traffic != null) {
            Spacer(Modifier.width(Space.m))
            Text(
                traffic,
                color = Muted,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
    }
}

@Composable
internal fun Dot(color: Color, size: Dp = Space.dot) {
    Box(Modifier.size(size).background(color, CircleShape))
}

/** Ширина колонки знака сервиса в строке и времени в истории. */
private val SERVICE_COLUMN = 44.dp

/** Протокол в строке не шире — имени сервера нужнее место. */
private val PROTOCOL_MAX = 96.dp

/** Точка — на середине первой строки текста рядом, время истории — на его базовой линии. */
private val DOT_TOP = 6.dp
private val TIME_TOP = 2.dp

/**
 * Карточка текущего VPN: строка на сервис (или одна строка связи с VPN), разделены линией. Строка — всегда две
 * строки текста ([ServiceLine]); тап по строке сервиса — шторка с подробностями ([ServiceSheet]). Под карточкой —
 * «повторить», если подъём не удался.
 */
@Composable
private fun VpnCard(rows: List<ServiceRow>, onRetry: () -> Unit, history: suspend (String) -> List<HistoryEvent>) {
    // Открытая шторка — по метке сервиса: строки обновляются, шторка показывает живую.
    var open by remember { mutableStateOf("") }
    Surface(
        shape = Corner,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Space.hairline, Rule),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(thickness = Space.hairline, color = Rule)
                ServiceLine(row, onTap = { open = row.label })
            }
        }
    }
    when (val row = rows.firstOrNull { it.label.isNotEmpty() && it.label == open }) {
        null -> Unit
        else -> ServiceSheet(row, history, onDismiss = { open = "" })
    }
    if (rows.any { it.retry }) {
        // Главной кнопкой: сейчас это единственное, что человек может сделать.
        PrimaryButton("повторить", onRetry, Modifier.fillMaxWidth().padding(top = Space.m))
    }
    Spacer(Modifier.height(Space.l))
}

/** Сколько событий истории в шторке: без прокрутки — столько, сколько влезает на телефоне. */
private const val HISTORY_SHOWN = 6

private val CLOCK = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/**
 * Шторка сервиса по тапу на строку: заголовок — сервис, сервер и протокол; «сейчас» — мини-статус, величины пары и все
 * длинные слова строки (тупик, переезд сети, причины); ниже — история сервиса ([history], свёртка Ленты), свежие
 * сверху. Без прокрутки — только то, что влезает; смахнуть — закрыть.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceSheet(row: ServiceRow, history: suspend (String) -> List<HistoryEvent>, onDismiss: () -> Unit) {
    var events by remember(row.label) { mutableStateOf(emptyList<HistoryEvent>()) }
    LaunchedEffect(row.label) { events = history(row.label).take(HISTORY_SHOWN) }
    PodborSheet(rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismiss, title = row.label) {
        Text(
            listOfNotNull(row.name, row.protocol).joinToString(" · "),
            color = PodborColors.Text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.m))
        Text(row.mini.text, color = colorOf(row.mini.tone), style = MaterialTheme.typography.bodyMedium)
        // Величины пары — если добавляют к мини-статусу («сейчас не пользуется» там уже сказано).
        row.pulse?.takeUnless { it in row.mini.text }?.let {
            Text(it, color = Muted, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = Space.xs))
        }
        // Заметки — кроме уже сказанной мини-статусом.
        row.notes.filterNot { it.text in row.mini.text }.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(top = Space.m), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = DOT_TOP)) { Dot(colorOf(n.tone)) }
                Spacer(Modifier.width(Space.m))
                Text(n.text, color = if (n.tone == Tone.Neutral) PodborColors.Text else Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (events.isNotEmpty()) {
            Text(
                "ИСТОРИЯ",
                color = Muted,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = TierSpacing),
                modifier = Modifier.padding(top = Space.xl, bottom = Space.xs),
            )
            events.forEach { e ->
                Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.Top) {
                    Text(
                        CLOCK.format(java.time.Instant.ofEpochMilli(e.atMs).atZone(java.time.ZoneId.systemDefault())),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.width(SERVICE_COLUMN).padding(top = TIME_TOP),
                    )
                    Box(Modifier.padding(top = DOT_TOP)) { Dot(colorOf(toneOf(e.mood))) }
                    Spacer(Modifier.width(Space.m))
                    Text(e.text, color = PodborColors.Text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun toneOf(m: Mood): Tone = when (m) {
    Mood.Good -> Tone.Ok
    Mood.Bad -> Tone.Fail
    Mood.Info -> Tone.Muted
}

/** Тег строки сервиса для UI-тестов. */
const val SERVICE_TAG = "service"

/**
 * Одна строка карточки — всегда две строки текста, высота не прыгает ни в каком состоянии: слева отметка с меткой
 * сервиса и секундомер, в середине сервер и мини-статус ([ServiceRow.mini]), справа протокол. Каждый текст — в одну
 * строку с многоточием; пустое место держит строку той же высоты. Длинные слова — в шторке ([onTap]).
 */
@Composable
internal fun ServiceLine(row: ServiceRow, onTap: () -> Unit) {
    val tappable = row.label.isNotEmpty()
    Box(
        Modifier
            .testTag(SERVICE_TAG)
            .then(if (tappable) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = Space.l, vertical = Space.m).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tappable) {
                // Состояние — цветом точки, сервис — его знаком одним цветом (нет знака — меткой словом).
                val color = when (row.mark) {
                    Mark.Checking -> PodborColors.Accent
                    Mark.Works -> PodborColors.Ok
                    Mark.Dead -> PodborColors.Fail
                    null -> Idle
                }
                // Под знаком — секундомер подбора этого сервиса (с десятыми); подбора не было — пустая строка той же высоты.
                Column(Modifier.widthIn(min = SERVICE_COLUMN)) {
                    Row(Modifier.height(Space.icon + Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Dot(color)
                        Spacer(Modifier.width(Space.s))
                        when (val glyph = glyphOf(row.service)) {
                            null -> Text(row.label, color = PodborColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            else -> Icon(painterResource(glyph), contentDescription = row.label, tint = PodborColors.Text, modifier = Modifier.size(Space.icon))
                        }
                    }
                    Text(
                        elapsedOf(row.stopwatch)?.let(::cardTime).orEmpty(),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                }
                VerticalDivider(color = Rule, modifier = Modifier.fillMaxHeight().padding(horizontal = Space.m))
            }
            Column(Modifier.weight(1f)) {
                Text(row.name, color = PodborColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    row.mini.text,
                    color = colorOf(row.mini.tone),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            row.protocol?.let { p ->
                VerticalDivider(color = Rule, modifier = Modifier.fillMaxHeight().padding(horizontal = Space.m))
                Column {
                    Text("ПРОТОКОЛ", color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text(
                        p,
                        color = PodborColors.Text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = PROTOCOL_MAX),
                    )
                }
            }
            if (tappable) Text("›", color = Muted, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.s))
        }
        // Полоска — только во время поиска: после результата «1 из 92» читалась бы как 1 %. Лежит поверх нижнего отступа
        // строки, а не своей строкой: появляясь и уходя, не двигает то, что ниже.
        AnimatedVisibility(visible = row.searching, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            LinearProgressIndicator(
                color = PodborColors.Accent,
                trackColor = Rule,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l).padding(bottom = Space.xs).height(Space.hairline * 3),
            )
        }
    }
}
