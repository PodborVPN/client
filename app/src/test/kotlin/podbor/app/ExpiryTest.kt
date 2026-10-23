package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.sub.Reminded
import podbor.app.sub.expiryWords
import podbor.app.sub.reminderDay
import java.time.LocalDateTime
import java.time.ZoneId

/** Напоминание о конце срока подписки: за 3, 2, 1 и 0 календарных дней, по разу на день и срок. */
class ExpiryTest : FunSpec({
    val zone = ZoneId.of("Europe/Moscow")
    fun ms(d: Int, h: Int = 12) = LocalDateTime.of(2026, 11, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    // Срок — 10 ноября, 03:00 по Москве.
    val expireS = LocalDateTime.of(2026, 11, 10, 3, 0).atZone(zone).toEpochSecond()

    test("за 3, 2, 1 и 0 календарных дней — напомнить; раньше, после срока и без срока — нет") {
        reminderDay(expireS, ms(6), zone, null) shouldBe null
        reminderDay(expireS, ms(7), zone, null) shouldBe 3
        reminderDay(expireS, ms(8, h = 23), zone, null) shouldBe 2
        reminderDay(expireS, ms(9, h = 0), zone, null) shouldBe 1
        reminderDay(expireS, ms(10, h = 1), zone, null) shouldBe 0
        reminderDay(expireS, ms(11), zone, null) shouldBe null
        reminderDay(0, ms(9), zone, null) shouldBe null
        reminderDay(null, ms(9), zone, null) shouldBe null
    }

    test("по разу на день: тот же день того же срока — молчим; следующий день — напомнить; продлили — счёт заново") {
        reminderDay(expireS, ms(8), zone, Reminded(expireS, 2)) shouldBe null
        reminderDay(expireS, ms(9), zone, Reminded(expireS, 2)) shouldBe 1
        val renewed = expireS + 30 * 86_400
        reminderDay(renewed, ms(9), zone, Reminded(expireS, 1)) shouldBe null
        reminderDay(renewed, LocalDateTime.of(2026, 12, 7, 12, 0).atZone(zone).toInstant().toEpochMilli(), zone, Reminded(expireS, 1)) shouldBe 3
    }

    test("слова: сколько осталось и чья подписка") {
        expiryWords(3, "Мой VPN") shouldBe ("Подписка кончается через 3 дня" to "Мой VPN — продли у провайдера")
        expiryWords(2, null) shouldBe ("Подписка кончается через 2 дня" to "Продли у провайдера")
        expiryWords(1, null).first shouldBe "Подписка кончается завтра"
        expiryWords(0, null).first shouldBe "Подписка кончается сегодня"
    }
})
