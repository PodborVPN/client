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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.launch
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

/** Отметка сервиса на карточке: «TG ✓», «IG ✗», «YT …». */
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
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp, bottom = 12.dp),
    ) {
        Head(board)
        if (board == null) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = PodborColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
                Text("читаю подписку…", color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
        } else {
            Summary(board)
            Feed(board.cards, Modifier.weight(1f))
            Verdict(board.verdict)
        }
        Ok(board, onOk)
    }
}

/** Заголовок, «готово / всего» и тонкая полоса прогресса; доски ещё нет — полоса бежит без числа. */
@Composable
private fun Head(board: ScanBoard?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            "АНАЛИЗИРУЮ ПОДПИСКУ",
            color = PodborColors.Accent,
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.06.em),
            modifier = Modifier.weight(1f),
        )
        if (board != null) Text("${board.done} / ${board.total}", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
    val bar = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).height(3.dp)
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
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip("", board.total, " " + plural(board.total, "сервер", "сервера", "серверов"), PodborColors.Accent)
        Chip("", board.ruEntries, " " + plural(board.ruEntries, "вход", "входа", "входов") + " в РФ", PodborColors.Accent)
        Chip("", board.likelyWhite, " вероятно для белых списков", PodborColors.Accent)
        board.tallies.forEach { t -> Chip("${t.label} ✓ ", t.works, "", PodborColors.Ok) }
    }
}

@Composable
private fun Chip(before: String, n: Int, after: String, accent: Color) {
    val shown by animateIntAsState(n, tween(400), label = "фишка")
    Box(
        Modifier
            .border(1.dp, Rule, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            buildAnnotatedString {
                append(before)
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(shown.toString()) }
                append(after)
            },
            color = PodborColors.Text,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Лента: [FEED_CARDS] новейших, новейшая сверху; внизу — затухание в фон, как в макете. LazyColumn + animateItem: въезд,
 * сдвиг соседей и уход — анимации размещения (фаза отрисовки), без перекладки всей ленты на каждом кадре.
 */
@Composable
private fun Feed(cards: List<ServerCard>, modifier: Modifier) {
    val fade = MaterialTheme.colorScheme.background
    val list = rememberLazyListState()
    val shown = cards.take(FEED_CARDS)
    // LazyColumn держит якорь на прежней первой карточке: новая сверху ушла бы за край. Пока смотрят в начало — вернуть.
    LaunchedEffect(shown.firstOrNull()?.key) {
        if (list.firstVisibleItemIndex <= 1) list.requestScrollToItem(0)
    }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val h = 72.dp.toPx().coerceAtMost(size.height)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, fade), startY = size.height - h, endY = size.height), Offset(0f, size.height - h))
            },
        state = list,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
    ) {
        items(shown, key = { it.key }) { card ->
            Box(Modifier.animateItem(fadeInSpec = tween(450), placementSpec = tween(450), fadeOutSpec = tween(300))) { Card(card) }
        }
    }
}

@Composable
private fun Card(card: ServerCard) {
    val edge by animateColorAsState(if (card.live) PodborColors.Accent.copy(alpha = 0.4f) else Rule, tween(400), label = "рамка")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, edge),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(routeOf(card), Modifier.weight(1f), transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "маршрут") {
                    Text(it, color = PodborColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                Text(
                    transportWords(card.transport).uppercase(),
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.em),
                )
            }
            val sni = if (card.sniWhite) " · SNI белый" else ""
            when (card.whiteHint) {
                WhiteHint.Likely -> Line("белые списки: вероятно$sni", PodborColors.Accent)
                WhiteHint.Unlikely -> Line("белые списки: нет$sni", Muted)
            }
            Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                card.services.forEachIndexed { i, (label, mark) ->
                    if (i > 0) Text(" · ", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    AnimatedContent(mark, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = label) { m ->
                        Text(markOf(label, m), color = colorOf(m), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
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
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
}

/** Итог по сценариям — словами ядра; появляется, когда ядро его дало. */
@Composable
private fun Verdict(lines: List<String>) {
    AnimatedVisibility(visible = lines.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            lines.forEach { Text(it, color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
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
        shape = RoundedCornerShape(12.dp),
        color = fill,
        border = BorderStroke(1.dp, if (finished) PodborColors.Accent else Rule),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Text(
                if (finished) "ОК" else "ОК, дальше",
                color = if (finished) PodborColors.Background else Muted,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    if (asking) SkipSheet(board, onStay = { asking = false }, onSkip = { asking = false; onOk() })
}

/** Шторка «Пропустить анализ?»: досмотреть — акцентом (так подбор быстрее), пропустить — тише. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkipSheet(board: ScanBoard?, onStay: () -> Unit, onSkip: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close(then: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { then() }
    }
    ModalBottomSheet(
        onDismissRequest = onStay,
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
            Text("Пропустить анализ?", color = PodborColors.Text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(skipProgress(board), color = PodborColors.Accent, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(SKIP_WARNING, color = Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = { close(onStay) },
                shape = RoundedCornerShape(12.dp),
                color = PodborColors.Accent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text("Досмотреть", color = PodborColors.Background, style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = { close(onSkip) },
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, Rule),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text("Пропустить", color = Muted, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
