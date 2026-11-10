package podbor.app.sub

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** За сколько календарных дней до конца срока напоминать. */
private val REMIND_DAYS = 0..3

/** О каком сроке и за сколько дней уже напомнили. */
@Serializable
data class Reminded(val expireS: Long, val day: Int)

/**
 * Напомнить о конце срока подписки [expireS] (секунды Unix; 0 и null — бессрочно) в [nowMs]: календарных дней до срока
 * в поясе [zone] — 3, 2, 1 или 0, и за этот день этого срока ещё не напоминали ([last]). Иначе null. Продлили — срок
 * другой, счёт заново.
 */
fun reminderDay(expireS: Long?, nowMs: Long, zone: ZoneId, last: Reminded?): Int? {
    if (expireS == null || expireS <= 0) return null
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val end = Instant.ofEpochSecond(expireS).atZone(zone).toLocalDate()
    val day = ChronoUnit.DAYS.between(today, end).toInt().takeIf { it in REMIND_DAYS } ?: return null
    return day.takeIf { last != Reminded(expireS, it) }
}

/** Заголовок и текст напоминания за [day] дней; [title] — имя подписки от провайдера. */
fun expiryWords(day: Int, title: String?): Pair<String, String> {
    val head = when (day) {
        0 -> "Подписка кончается сегодня"
        1 -> "Подписка кончается завтра"
        else -> "Подписка кончается через $day дня"
    }
    return head to (title?.let { "$it — продли у провайдера" } ?: "Продли у провайдера")
}
