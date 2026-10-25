package podbor.app.sub

import podbor.core.plural
import podbor.screen.Analyzed
import podbor.screen.SubscriptionCard
import podbor.screen.Term
import java.time.Instant
import java.time.ZoneId

/** Тон слова на карточке: обычный, внимание (янтарь), беда (красный). */
enum class Tone { Plain, Warn, Bad }

data class Word(val text: String, val tone: Tone)

/** Месяц коротко, в родительном: «до 13 янв» — короче даты цифрами и не зависит от языка телефона. */
private val MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

private fun dayMonth(atS: Long, zone: ZoneId): String {
    val d = Instant.ofEpochSecond(atS).atZone(zone)
    return "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
}

/** Строка под именем подписки: сколько серверов, чем кончился анализ, срок (неизвестный — не пишем). */
fun cardWords(c: SubscriptionCard, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Word> =
    listOf(Word("${c.servers} ${plural(c.servers, "сервер", "сервера", "серверов")}", Tone.Plain), analyzedWord(c.analyzed, nowMs)) +
        termWords(c.term, nowMs, zone)

private fun analyzedWord(a: Analyzed, nowMs: Long): Word = when (a) {
    Analyzed.Never -> Word("без анализа", Tone.Plain)
    is Analyzed.Done -> Word("анализ ${ago(nowMs - a.atMs)}", Tone.Plain)
    is Analyzed.Interrupted -> Word("анализ прерван", Tone.Warn)
}

private fun termWords(t: Term, nowMs: Long, zone: ZoneId): List<Word> = when (t) {
    Term.Unknown -> emptyList()
    is Term.Until -> listOf(Word("до ${dayMonth(t.atS, zone)}", Tone.Plain))
    is Term.Expired -> listOf(Word("кончилась ${ago(nowMs - t.atS * 1000)}", Tone.Bad))
    Term.OutOfTraffic -> listOf(Word("трафик кончился", Tone.Bad))
}

/** Анализ в шторке подписки: не было; давность; прерван — сколько серверов проверено из скольких. */
fun analyzedDetail(a: Analyzed, nowMs: Long): String = when (a) {
    Analyzed.Never -> "не было"
    is Analyzed.Done -> ago(nowMs - a.atMs)
    is Analyzed.Interrupted -> "прерван · ${a.done} из ${a.total}"
}
