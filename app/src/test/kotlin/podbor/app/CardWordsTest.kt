package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.sub.Tone
import podbor.app.sub.Word
import podbor.app.sub.analyzedDetail
import podbor.app.sub.cardWords
import podbor.app.sub.Fact
import podbor.app.sub.factRows
import podbor.core.Instagram
import podbor.core.LastWorked
import podbor.core.SubscriptionFacts
import podbor.core.Telegram
import podbor.core.Traffic
import podbor.core.Worked
import podbor.screen.Analyzed
import podbor.screen.Announce
import podbor.screen.SubscriptionCard
import podbor.screen.SubscriptionNote
import podbor.screen.Support
import podbor.screen.Term
import java.time.ZoneOffset

private const val DAY = 86_400_000L

/** Строка под именем подписки: серверы, анализ, срок — словами и тоном. */
class CardWordsTest : FunSpec({
    fun card(servers: Int = 24, analyzed: Analyzed = Analyzed.Never, term: Term = Term.Unknown) =
        SubscriptionCard("a", "имя", servers, analyzed, term, SubscriptionNote(null, emptyList(), Support.None, Announce.None))

    test("серверы со склонением, без анализа, срок неизвестен — не пишем") {
        cardWords(card(servers = 1), 0, ZoneOffset.UTC) shouldBe listOf(Word("1 сервер", Tone.Plain), Word("без анализа", Tone.Plain))
        cardWords(card(servers = 3), 0, ZoneOffset.UTC).first() shouldBe Word("3 сервера", Tone.Plain)
    }
    test("анализ: прошёл — давность; прерван — янтарём") {
        cardWords(card(analyzed = Analyzed.Done(0)), 2 * DAY, ZoneOffset.UTC)[1] shouldBe Word("анализ 2 дн назад", Tone.Plain)
        cardWords(card(analyzed = Analyzed.Interrupted(0, 5, 8)), 0, ZoneOffset.UTC)[1] shouldBe Word("анализ прерван", Tone.Warn)
    }
    test("срок: оплачена до — день и месяц словом; кончилась — красным с давностью; трафик кончился — красным") {
        cardWords(card(term = Term.Until(12 * 86_400L)), 0, ZoneOffset.UTC).last() shouldBe Word("до 13 янв", Tone.Plain)
        cardWords(card(term = Term.Until(40 * 86_400L)), 0, ZoneOffset.UTC).last() shouldBe Word("до 10 фев", Tone.Plain)
        cardWords(card(term = Term.Expired(0)), 3 * DAY, ZoneOffset.UTC).last() shouldBe Word("кончилась 3 дн назад", Tone.Bad)
        cardWords(card(term = Term.OutOfTraffic), 0, ZoneOffset.UTC).last() shouldBe Word("трафик кончился", Tone.Bad)
    }
    test("шторка: как подписка ведёт себя здесь — сервисы, белые списки, ответили, через Podbor, анализ") {
        val facts = SubscriptionFacts(
            worked = listOf(Worked(Telegram, LastWorked.At(0)), Worked(Instagram, LastWorked.Never)),
            ruEntries = 16, likelyWhite = 23, answered = 61, checked = 79,
        )
        val c = card(analyzed = Analyzed.Done(0)).copy(facts = facts, viaPodbor = Traffic(up = 200 * 1_048_576L, down = 3 * 1_073_741_824L))
        factRows(c, 2 * 3_600_000L) shouldBe listOf(
            Fact("Телега здесь", Word("работала 2 ч назад", Tone.Plain)),
            Fact("Инста здесь", Word("ещё не работала", Tone.Plain)),
            Fact("белые списки", Word("вероятно 23 · входов в РФ 16", Tone.Plain)),
            Fact("ответили", Word("61 из 79", Tone.Plain)),
            Fact("через Podbor", Word("3,2 ГБ", Tone.Plain)),
            Fact("анализ", Word("2 ч назад", Tone.Plain)),
        )
    }
    test("шторка: без анализа и трафика — только сервисы и анализ") {
        val c = card().copy(facts = SubscriptionFacts.Empty.copy(worked = listOf(Worked(Telegram, LastWorked.Never))))
        factRows(c, 0) shouldBe listOf(Fact("Телега здесь", Word("ещё не работала", Tone.Plain)), Fact("анализ", Word("не было", Tone.Plain)))
    }

    test("анализ в шторке: не было; давность; прерван — сколько из скольких") {
        analyzedDetail(Analyzed.Never, 0) shouldBe "не было"
        analyzedDetail(Analyzed.Done(0), 30_000) shouldBe "только что"
        analyzedDetail(Analyzed.Interrupted(0, 5, 8), 0) shouldBe "прерван · 5 из 8"
    }
})
