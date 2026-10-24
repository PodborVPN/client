package podbor.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.Mark3
import podbor.core.ScanBoard
import podbor.core.ServerCard
import podbor.core.WhiteHint
import podbor.core.plural
import podbor.core.transportWords
import java.util.Locale

/*
 * Экран анализа подписки: живая сводка, лента карточек серверов (новейшая сверху въезжает и сдвигает прошлые вниз,
 * строки заполняются по мере замеров), итог и «ОК». Сервисы — только из доски (метки ядра), без имён в коде экрана.
 */

private val RU = Locale.forLanguageTag("ru")

/** Сколько новейших карточек лента рисует (как в макете: нижняя гаснет градиентом); прочие не композируются. */
const val FEED_CARDS = 6

/** Флаг из двухбуквенного кода страны (региональные индикаторы); не код — "". */
fun flagOf(code: String): String {
    val c = code.uppercase(Locale.ROOT)
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
    return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** «🇳🇱 Нидерланды» по коду; не код — «?». */
fun placeOf(code: String): String {
    val flag = flagOf(code)
    if (flag.isEmpty()) return "?"
    val name = Locale.forLanguageTag("und-${code.uppercase(Locale.ROOT)}").getDisplayCountry(RU).ifEmpty { code }
    return "$flag $name"
}

/** Вход → выход; выход ещё меряем — «…», не узнали — «?». */
fun routeOf(card: ServerCard): String {
    val entry = when (val e = card.entry) {
        Entry.Ru -> placeOf("RU")
        is Entry.Foreign -> placeOf(e.country)
        Entry.Unknown -> "?"
    }
    val exit = when (val x = card.exit) {
        is Exit.At -> placeOf(x.country)
        Exit.Unknown -> if (card.live) "…" else "?"
    }
    return "$entry → $exit"
}

/** Ширина знака сервиса в фишке — по строке мелкого текста. */
private val CHIP_SIGN = 14.dp

/** Отметка сервиса на карточке словами — для озвучки: «TG ✓», «IG ✗», «YT …». */
fun markOf(label: String, mark: Mark3): String = when (mark) {
    Mark3.Pending -> "$label …"
    Mark3.Works -> "$label ✓"
    Mark3.Dead -> "$label ✗"
}

@Composable
fun ScanScreen(board: ScanBoard?, onOk: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = Space.screen)
            .padding(vertical = Space.m),
    ) {
        Head(board)
        if (board == null) {
            // Полоса над строкой уже бежит — своей крутилки не нужно.
            Text("читаю подписку…", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
        } else {
            Summary(board)
            // Знак сервиса по метке карточки — из фишек итога: в них и сервис, и метка.
            Feed(board.cards, board.tallies.associate { it.label to glyphOf(it.service) }, Modifier.weight(1f))
            Verdict(board.verdict)
        }
        Ok(board, onOk)
    }
}

/** Заголовок, «готово / всего» и тонкая полоса прогресса; доски ещё нет — полоса бежит без числа. */
@Composable
private fun Head(board: ScanBoard?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Caps("анализирую подписку", MaterialTheme.typography.titleMedium, PodborColors.Accent, Modifier.weight(1f))
        if (board != null) {
            Text(
                "${board.done} / ${board.total}",
                color = Muted,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
            )
        }
    }
    val bar = Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.m).height(Space.xs)
    if (board == null || board.total == 0) {
        LinearProgressIndicator(color = PodborColors.Accent, trackColor = Rule, modifier = bar)
    } else {
        val part by animateFloatAsState(board.done.toFloat() / board.total, tween(600), label = "прогресс")
        LinearProgressIndicator(progress = { part }, color = PodborColors.Accent, trackColor = Rule, drawStopIndicator = {}, gapSize = 0.dp, modifier = bar)
    }
}

/** Живая сводка: серверы, входы в РФ, вероятно-белые и по фишке на сервис набора; числа докручиваются. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(board: ScanBoard) {
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Chip("", board.total, " " + plural(board.total, "сервер", "сервера", "серверов"), PodborColors.Accent, board.total)
        Chip("", board.ruEntries, " " + plural(board.ruEntries, "вход", "входа", "входов") + " в РФ", PodborColors.Accent, board.total)
        Chip("", board.likelyWhite, " вероятно для белых списков", PodborColors.Accent, board.total)
        // Сервис — знаком (нет знака — меткой словом), подписанным меткой для озвучки.
        board.tallies.forEach { t ->
            when (val glyph = glyphOf(t.service)) {
                null -> Chip("${t.label} ✓ ", t.works, "", PodborColors.Ok, board.total)
                else -> Chip("✓ ", t.works, "", PodborColors.Ok, board.total, ServiceSign(glyph, t.label))
            }
        }
    }
}

/** Знак сервиса и его метка — для озвучки и поиска. */
private data class ServiceSign(@DrawableRes val glyph: Int, val label: String)

/**
 * Фишка держит ширину под число из стольких же цифр, сколько в [cap]: растущее число не переносит соседей в FlowRow.
 * [sign] — знак сервиса перед словами.
 */
@Composable
private fun Chip(before: String, n: Int, after: String, accent: Color, cap: Int, sign: ServiceSign? = null) {
    val shown by animateIntAsState(n, tween(400), label = "фишка")
    fun text(number: String) = buildAnnotatedString {
        append(before)
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(number) }
        append(after)
    }
    Row(
        Modifier
            .border(Space.hairline, Rule, Corner)
            .padding(horizontal = Space.s, vertical = Space.xs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sign != null) {
            Icon(painterResource(sign.glyph), contentDescription = sign.label, tint = PodborColors.Text, modifier = Modifier.size(CHIP_SIGN))
            Spacer(Modifier.width(Space.xs))
        }
        Box {
            Placeholders(listOf(text("8".repeat(cap.toString().length))), MaterialTheme.typography.labelSmall)
            Text(text(shown.toString()), color = PodborColors.Text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Невидимые заглушки — место под самый широкий из [variants]: текст рядом с ними меняется, а соседи стоят. Вне
 * семантики: тесты и TalkBack видят только настоящий текст.
 */
@Composable
private fun Placeholders(variants: List<AnnotatedString>, style: TextStyle) {
    variants.forEach { Text(it, style = style, maxLines = 1, modifier = Modifier.alpha(0f).clearAndSetSemantics {}) }
}

/**
 * Лента: [FEED_CARDS] новейших, новейшая сверху. LazyColumn + animateItem: въезд,
 * сдвиг соседей и уход — анимации размещения (фаза отрисовки), без перекладки всей ленты на каждом кадре.
 */
@Composable
private fun Feed(cards: List<ServerCard>, glyphs: Map<String, Int?>, modifier: Modifier) {
    val list = rememberLazyListState()
    val shown = cards.take(FEED_CARDS)
    // LazyColumn держит якорь на прежней первой карточке: новая сверху ушла бы за край. Пока смотрят в начало — вернуть.
    LaunchedEffect(shown.firstOrNull()?.key) {
        if (list.firstVisibleItemIndex <= 1) list.requestScrollToItem(0)
    }
    LazyColumn(
        // Обрезка снаружи ленты: карточки, уходящие и въезжающие с анимацией, рисуются слоями и иначе на миг ложатся
        // поверх вердикта и «ОК» под лентой.
        modifier.fillMaxWidth().clipToBounds(),
        state = list,
        verticalArrangement = Arrangement.spacedBy(Space.s),
        contentPadding = PaddingValues(bottom = Space.s),
    ) {
        items(shown, key = { it.key }) { card ->
            Box(Modifier.animateItem(fadeInSpec = tween(450), placementSpec = tween(450), fadeOutSpec = tween(300))) { Card(card, glyphs) }
        }
    }
}

@Composable
private fun Card(card: ServerCard, glyphs: Map<String, Int?>) {
    val edge by animateColorAsState(if (card.live) PodborColors.Accent.copy(alpha = DIM) else Rule, tween(400), label = "рамка")
    Surface(
        shape = Corner,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(Space.hairline, edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(routeOf(card), Modifier.weight(1f), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) using null }, label = "маршрут") {
                    Text(it, color = PodborColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Caps(transportWords(card.transport), MaterialTheme.typography.labelSmall, Muted)
            }
            val sni = if (card.sniWhite) " · SNI белый" else ""
            when (card.whiteHint) {
                WhiteHint.Likely -> Line("белые списки: вероятно$sni", PodborColors.Accent)
                WhiteHint.Unlikely -> Line("белые списки: нет$sni", Muted)
            }
            // Сервис — точкой состояния и знаком, как в карточке текущего VPN: ширина одна при любой отметке.
            Row(
                Modifier.padding(top = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                card.services.forEach { (label, mark) -> ServiceMark(label, mark, glyphs[label]) }
            }
        }
    }
}

/** Отметка сервиса на карточке: точка цвета отметки и знак сервиса (нет знака — метка); озвучка — «TG ✓». */
@Composable
private fun ServiceMark(label: String, mark: Mark3, @DrawableRes glyph: Int?) {
    val color by animateColorAsState(colorOf(mark), tween(300), label = label)
    Row(
        Modifier.clearAndSetSemantics { contentDescription = markOf(label, mark) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Spacer(Modifier.width(Space.xs))
        when (glyph) {
            null -> Text(label, color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            else -> Icon(painterResource(glyph), contentDescription = null, tint = PodborColors.Text, modifier = Modifier.size(Space.icon))
        }
    }
}

private fun colorOf(m: Mark3): Color = when (m) {
    Mark3.Pending -> PodborColors.Accent
    Mark3.Works -> PodborColors.Ok
    Mark3.Dead -> PodborColors.Fail
}

@Composable
private fun Line(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xs))
}

/** Итог по сценариям — словами ядра; появляется, когда ядро его дало. */
@Composable
private fun Verdict(lines: List<String>) {
    AnimatedVisibility(visible = lines.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
            lines.forEach { Text(it, color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.xs)) }
        }
    }
}

/** Цена пропуска: непроверенные серверы подбор потом проверяет сам, на нажатии. */
const val SKIP_WARNING = "Если пропустить — подбор потом займёт больше времени: непроверенные серверы Podbor проверит на ходу."

/** Сколько уже проверено — над ценой пропуска; доски ещё нет — подписка читается. */
internal fun skipProgress(board: ScanBoard?): String =
    if (board == null) "Подписка ещё читается." else "Проверено ${board.done} из ${board.total}."

/**
 * «ОК» — когда анализ кончился; раньше — «ОК, дальше»: прервать, паспорт сохранится частичный. Срока у анализа нет
 * — до конца ведёт человек, поэтому «ОК, дальше» сначала показывает цену пропуска шторкой
 * снизу ([SkipSheet]). Анализ кончился, пока шторка открыта, — она закрывается: пропускать уже нечего.
 */
@Composable
private fun Ok(board: ScanBoard?, onOk: () -> Unit) {
    val finished = board?.finished == true
    var asking by remember { mutableStateOf(false) }
    LaunchedEffect(finished) { if (finished) asking = false }
    val fill by animateColorAsState(if (finished) PodborColors.Accent else Color.Transparent, tween(400), label = "ок")
    Surface(
        onClick = { if (finished) onOk() else asking = true },
        shape = Corner,
        color = fill,
        border = BorderStroke(Space.hairline, if (finished) PodborColors.Accent else Rule),
        modifier = Modifier.fillMaxWidth().padding(top = Space.m),
    ) {
        // Главная кнопка, пока анализ не кончился, — тихая: заливка приходит с концом анализа.
        Box(Modifier.fillMaxWidth().heightIn(min = Space.touch), contentAlignment = Alignment.Center) {
            Caps(if (finished) "ОК" else "ОК, дальше", MaterialTheme.typography.labelLarge, if (finished) PodborColors.Background else Muted)
        }
    }
    if (asking) SkipSheet(board, onStay = { asking = false }, onSkip = { asking = false; onOk() })
}

/** Шторка «Пропустить анализ?»: досмотреть — акцентом (так подбор быстрее), пропустить — тише. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkipSheet(board: ScanBoard?, onStay: () -> Unit, onSkip: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAnswer(state)
    PodborSheet(state, onDismiss = { close(onStay) }, title = "Пропустить анализ?") {
        Text(skipProgress(board), color = PodborColors.Accent, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Space.xs))
        Text(SKIP_WARNING, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Space.xl))
        Answers("Досмотреть", "Пропустить", onYes = { close(onStay) }, onNo = { close(onSkip) })
    }
}
