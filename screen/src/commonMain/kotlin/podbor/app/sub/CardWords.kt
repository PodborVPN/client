package podbor.app.sub

import podbor.core.LastWorked
import podbor.core.plural
import podbor.screen.Analyzed
import podbor.screen.SubscriptionCard
import podbor.screen.Term
import podbor.screen.trafficLine

/** Местные день месяца и номер месяца (1..12): часовой пояс знает платформа. */
data class DayMonth(val day: Int, val month: Int)

/** Тон слова на карточке: обычный, внимание (янтарь), беда (красный). */
enum class Tone { Plain, Warn, Bad }

data class Word(val text: String, val tone: Tone)

/** Месяц коротко, в родительном: «до 13 янв» — короче даты цифрами и не зависит от языка телефона. */
private val MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

private fun words(d: DayMonth): String = "${d.day} ${MONTHS[d.month - 1]}"

/** Строка под именем подписки: сколько серверов, чем кончился анализ, срок (неизвестный — не пишем). */
fun cardWords(c: SubscriptionCard, nowMs: Long, dayOf: (atS: Long) -> DayMonth): List<Word> =
    listOf(Word("${c.servers} ${plural(c.servers, "сервер", "сервера", "серверов")}", Tone.Plain), analyzedWord(c.analyzed, nowMs)) +
        termWords(c.term, nowMs, dayOf)

private fun analyzedWord(a: Analyzed, nowMs: Long): Word = when (a) {
    Analyzed.Never -> Word("без анализа", Tone.Plain)
    is Analyzed.Done -> Word("анализ ${ago(nowMs - a.atMs)}", Tone.Plain)
    is Analyzed.Interrupted -> Word("анализ прерван", Tone.Warn)
}

private fun termWords(t: Term, nowMs: Long, dayOf: (Long) -> DayMonth): List<Word> = when (t) {
    Term.Unknown -> emptyList()
    is Term.Until -> listOf(Word("до ${words(dayOf(t.atS))}", Tone.Plain))
    is Term.Expired -> listOf(Word("кончилась ${ago(nowMs - t.atS * 1000)}", Tone.Bad))
    Term.OutOfTraffic -> listOf(Word("трафик кончился", Tone.Bad))
}

/** Строка шторки подписки: что ([key]) и словами с тоном ([value]). */
data class Fact(val key: String, val value: Word)

/**
 * Шторка подписки — как она ведёт себя на этом телефоне: работал ли каждый сервис на этой сети; по анализу — белые
 * списки и сколько ответило (анализа не было — не пишем); сколько прошло через VPN на ней (ничего — не пишем); анализ.
 * Только факты, без оценок «хорошая/плохая».
 */
fun factRows(c: SubscriptionCard, nowMs: Long): List<Fact> {
    val f = c.facts
    val analyzed = if (f.checked > 0) {
        listOf(
            Fact("белые списки", Word("вероятно ${f.likelyWhite} · входов в РФ ${f.ruEntries}", Tone.Plain)),
            Fact("ответили", Word("${f.answered} из ${f.checked}", Tone.Plain)),
        )
    } else {
        emptyList()
    }
    val via = c.viaPodbor.up + c.viaPodbor.down
    return f.worked.map { Fact("${it.service.title} здесь", Word(workedWords(it.last, nowMs), Tone.Plain)) } +
        analyzed +
        (if (via > 0) listOf(Fact("через Podbor", Word(trafficLine(c.viaPodbor), Tone.Plain))) else emptyList()) +
        Fact("анализ", Word(analyzedDetail(c.analyzed, nowMs), Tone.Plain))
}

private fun workedWords(last: LastWorked, nowMs: Long): String = when (last) {
    LastWorked.Never -> "ещё не работала"
    is LastWorked.At -> "работала ${ago(nowMs - last.atMs)}"
}

/** Давность словами: «только что», «N мин назад», «N ч назад», «N дн назад». */
fun ago(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 48 * 60 -> "${min / 60} ч назад"
        else -> "${min / (24 * 60)} дн назад"
    }
}

/** Анализ в шторке подписки: не было; давность; прерван — сколько серверов проверено из скольких. */
fun analyzedDetail(a: Analyzed, nowMs: Long): String = when (a) {
    Analyzed.Never -> "не было"
    is Analyzed.Done -> ago(nowMs - a.atMs)
    is Analyzed.Interrupted -> "прерван · ${a.done} из ${a.total} · ${ago(nowMs - a.atMs)}"
}
