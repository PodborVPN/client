package podbor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import podbor.app.sub.Tone
import podbor.app.sub.analyzedDetail
import podbor.app.sub.cardWords
import podbor.screen.SubSheet
import podbor.screen.SubscriptionCard
import podbor.screen.Subscriptions
import podbor.screen.Support
import podbor.screen.card
import podbor.screen.cards
import podbor.screen.isCurrent

/** Рамка текущей подписки — зелёная: янтарная у анализа, путать нельзя. */
private val CurrentBorder = 1.5.dp

/**
 * «Мои подписки» под кнопками вставки: текущая первой, в зелёной рамке; тап по всей карточке — её шторка ([onCard]).
 * Подписок нет — ничего.
 */
@Composable
internal fun SubscriptionList(subscriptions: Subscriptions, nowMs: Long, onCard: (SubscriptionCard) -> Unit, modifier: Modifier = Modifier) {
    val cards = subscriptions.cards()
    if (cards.isEmpty()) return
    LazyColumn(modifier.fillMaxWidth().padding(top = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item(key = "head") { Caps("мои подписки", MaterialTheme.typography.labelMedium, Muted, Modifier.padding(bottom = Space.xs)) }
        items(cards, key = { it.id }) { card ->
            SubscriptionRow(card, subscriptions.isCurrent(card.id), nowMs, { onCard(card) }, Modifier.animateItem())
        }
    }
}

@Composable
private fun SubscriptionRow(card: SubscriptionCard, current: Boolean, nowMs: Long, onClick: () -> Unit, modifier: Modifier) {
    val frame = if (current) Modifier.border(CurrentBorder, PodborColors.Ok, Corner) else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .clip(Corner)
            .background(PodborColors.Surface)
            .then(frame)
            .selectable(selected = current, onClick = onClick)
            .heightIn(min = Space.touch)
            .padding(horizontal = Space.m, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(card.title, color = PodborColors.Text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildAnnotatedString {
                    cardWords(card, nowMs).forEachIndexed { i, w ->
                        if (i > 0) append(" · ")
                        withStyle(SpanStyle(color = colorOf(w.tone))) { append(w.text) }
                    }
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = Muted, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = Space.s))
    }
}

private fun colorOf(t: Tone): Color = when (t) {
    Tone.Plain -> Muted
    Tone.Warn -> PodborColors.Accent
    Tone.Bad -> PodborColors.Fail
}

/**
 * Шторка подписки: сведения и действия ([SubSheet.Card]); подтверждение удаления ([SubSheet.Confirm]); выбор идёт —
 * что делаем ([SubSheet.Picking]); выбор не вышел — кончилась, к поддержке провайдера ([SubSheet.Ended]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSheet(
    sheet: SubSheet,
    nowMs: Long,
    onPick: (SubscriptionCard) -> Unit,
    onReanalyze: (SubscriptionCard) -> Unit,
    onAskDelete: (SubscriptionCard) -> Unit,
    onConfirmDelete: (SubscriptionCard) -> Unit,
    onSupport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAnswer(state)
    PodborSheet(state, onDismiss = onDismiss, title = sheet.card().title) {
        when (sheet) {
            is SubSheet.Card -> CardBody(sheet, nowMs, onPick, onReanalyze, onAskDelete)
            is SubSheet.Confirm -> ConfirmBody(sheet, onYes = { close { onConfirmDelete(sheet.card) } }, onNo = { close(onDismiss) })
            is SubSheet.Picking -> Text("обновляю подписку…", color = Muted, style = MaterialTheme.typography.bodyMedium)
            is SubSheet.Ended -> EndedBody(sheet.support) { url -> close { onSupport(url) } }
        }
    }
}

/** Сведения подписки и действия: прочей — «выбрать» главной; текущей «выбрать» не нужно — главная «проанализировать». */
@Composable
private fun ColumnScope.CardBody(
    sheet: SubSheet.Card,
    nowMs: Long,
    onPick: (SubscriptionCard) -> Unit,
    onReanalyze: (SubscriptionCard) -> Unit,
    onAskDelete: (SubscriptionCard) -> Unit,
) {
    val card = sheet.card
    Fact("анализ", analyzedDetail(card.analyzed, nowMs))
    card.note.lines.forEach { Text(it, color = Muted, style = MaterialTheme.typography.bodyMedium) }
    Spacer(Modifier.height(Space.l))
    if (sheet.current) {
        PrimaryButton("проанализировать заново", { onReanalyze(card) }, Modifier.fillMaxWidth())
    } else {
        PrimaryButton("выбрать", { onPick(card) }, Modifier.fillMaxWidth())
        Gap()
        QuietButton("проанализировать заново", { onReanalyze(card) }, Modifier.fillMaxWidth())
    }
    Gap()
    QuietButton("удалить", { onAskDelete(card) }, Modifier.fillMaxWidth(), color = PodborColors.Fail)
}

/** Подтверждение удаления; на подписке сейчас VPN — словами, что он выключится. */
@Composable
private fun ColumnScope.ConfirmBody(sheet: SubSheet.Confirm, onYes: () -> Unit, onNo: () -> Unit) {
    Text("Удалить подписку?", color = PodborColors.Text, style = MaterialTheme.typography.bodyLarge)
    if (sheet.stopsVpn) Text("VPN выключится", color = PodborColors.Fail, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(Space.l))
    QuietButton("удалить", onYes, Modifier.fillMaxWidth(), color = PodborColors.Fail)
    Gap()
    QuietButton("отмена", onNo, Modifier.fillMaxWidth(), color = Muted)
}

/** Выбор не вышел: подписка кончилась; провайдер дал поддержку — к ней (там продлевают). */
@Composable
private fun ColumnScope.EndedBody(support: Support, onSupport: (String) -> Unit) {
    Text("подписка кончилась", color = PodborColors.Fail, style = MaterialTheme.typography.bodyLarge)
    when (support) {
        is Support.Link -> {
            Spacer(Modifier.height(Space.l))
            PrimaryButton("поддержка провайдера", { onSupport(support.url) }, Modifier.fillMaxWidth())
        }
        Support.None -> Unit
    }
}

@Composable
private fun Fact(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Text(key, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = PodborColors.Text, style = MaterialTheme.typography.bodyMedium)
    }
}
