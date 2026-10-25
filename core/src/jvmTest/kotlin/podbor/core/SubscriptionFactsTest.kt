package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Как подписка ведёт себя на этом телефоне: свёртка паспорта и памяти по ключам её серверов. */
class SubscriptionFactsTest : FunSpec({
    fun facts(entry: Entry, hint: WhiteHint, seen: Seen) = ServerFacts(Transport.Ws, entry, hint, Exit.Unknown, seen)
    val passport = Passport(
        mapOf(
            "a" to facts(Entry.Ru, WhiteHint.Likely, Seen.Answered(1)),
            "b" to facts(Entry.Foreign("NL"), WhiteHint.Unlikely, Seen.Answered(1)),
            "c" to facts(Entry.Ru, WhiteHint.Unlikely, Seen.Silent),
            "z" to facts(Entry.Ru, WhiteHint.Likely, Seen.Answered(1)),
        ),
        analyzedAtMs = 5,
    )
    val mts = OsNetwork.Cellular("MTS RUS", "25001")
    val tg = MemoryKey("MTS RUS", Scenario.MobileOpen, Telegram.id)
    val tgWhite = MemoryKey("MTS RUS", Scenario.MobileWhitelist, Telegram.id)
    val tgWifi = MemoryKey("wifi", Scenario.Wifi, Telegram.id)
    val memory = Memory(
        mapOf(
            tg to nonEmptyListOf(Success("a", 10), Success("z", 90)),
            tgWhite to nonEmptyListOf(Success("b", 30)),
            tgWifi to nonEmptyListOf(Success("a", 99)),
        ),
        emptyMap(),
    )

    test("серверы подписки: входы в РФ, вероятно для белых, ответили из проверенных; чужие серверы паспорта не в счёт") {
        val f = subscriptionFacts(setOf("a", "b", "c", "n"), passport, Memory.Empty, mts, listOf(Telegram, Instagram))
        f.ruEntries shouldBe 2
        f.likelyWhite shouldBe 1
        f.answered shouldBe 2
        f.checked shouldBe 3
    }

    test("работала здесь — последний успех на этой сети (любой сценарий), только её серверы; не было — Never") {
        val f = subscriptionFacts(setOf("a", "b"), passport, memory, mts, listOf(Telegram, Instagram))
        f.worked shouldBe listOf(Worked(Telegram, LastWorked.At(30)), Worked(Instagram, LastWorked.Never))
    }

    test("другая сеть — свои успехи: на Wi-Fi работала в 99") {
        subscriptionFacts(setOf("a"), passport, memory, OsNetwork.Wifi, listOf(Telegram)).worked shouldBe
            listOf(Worked(Telegram, LastWorked.At(99)))
    }

    test("итог трафика подписки: текущий счёт, прошлый или ноль") {
        val book = TrafficBook(vpn = Tally("k1", Traffic(1, 2)), past = mapOf("k2" to Traffic(3, 4)))
        book.of("k1") shouldBe Traffic(1, 2)
        book.of("k2") shouldBe Traffic(3, 4)
        book.of("k3") shouldBe Traffic.Zero
    }
})
