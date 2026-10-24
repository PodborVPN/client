package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.Blame
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.NetworkMove
import podbor.core.OsNetwork
import podbor.core.PairPulse
import podbor.core.Since
import podbor.core.Relocation
import podbor.core.ServerId
import podbor.core.Transport

/**
 * Мини-статус строки сервиса — одна короткая фраза: работает ли и что сейчас происходит. Подробности — в шторке. Фраза
 * не зависит от длинных слов: строка не меняет высоту ни в каком состоянии.
 */
class MiniTest : FunSpec({

    val nl = ExitRef("NL-7", Transport.Grpc)
    val moved = Relocation.Moved(NetworkMove(OsNetwork.Wifi, OsNetwork.Cellular("MTS", "25001")))
    val flowing = PairPulse.Seen(downBps = 1_258_291, upBps = 12_000, answered = Since.Never)

    test("работает: с трафиком — скорость вниз; без пульса — просто «работает»; не пользуется — так и пишем") {
        miniOf(CardStatus.Works(nl), ExitOrigin.Chosen, flowing) shouldBe Note("работает · ↓ ${rateText(flowing.downBps)}", Tone.Ok)
        miniOf(CardStatus.Works(nl), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note("работает", Tone.Ok)
        miniOf(CardStatus.Works(nl), ExitOrigin.Chosen, PairPulse.Seen(0, 0, Since.Never)) shouldBe Note("работает · сейчас не пользуется", Tone.Ok)
    }

    test("найден подбором — чем проверен") {
        miniOf(CardStatus.Found(nl, 0, "проверено 2 из 11 входов РФ"), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe
            Note("найден · проверено 2 из 11 входов РФ", Tone.Ok)
    }

    test("проверяю: после переезда сети, из памяти или просто") {
        miniOf(CardStatus.Checking(nl, moved), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note("сеть сменилась — проверяю", Tone.Warn)
        miniOf(CardStatus.Checking(nl), ExitOrigin.Recalled, PairPulse.Unseen) shouldBe Note("последний рабочий — проверяю", Tone.Warn)
        miniOf(CardStatus.Checking(nl), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note("проверяю", Tone.Warn)
        miniOf(CardStatus.Searching, ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note("подбираю…", Tone.Warn)
    }

    test("мёртв — причина коротко; не найден — первая строка объяснения") {
        mapOf(
            Blame.Whitelist to "белые списки",
            Blame.SuspectedWhitelist to "похоже на белые списки",
            Blame.NoNetwork to "нет сети",
            Blame.Server to "сервер не отвечает",
            Blame.Unexplained to "не отвечает",
        ).forEach { (blame, words) -> miniOf(CardStatus.Dead(nl, blame), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note(words, Tone.Fail) }
        miniOf(CardStatus.NotFound(listOf("Белые списки: заграница не отвечает.", "Проверил 3 входа")), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe
            Note("Белые списки: заграница не отвечает.", Tone.Fail)
    }

    test("выход не выбран или не из подписки — серым") {
        miniOf(CardStatus.Unknown, ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note("Xray выбирает сам", Tone.Muted)
        miniOf(CardStatus.Unlisted(ServerId("s042")), ExitOrigin.Chosen, PairPulse.Unseen) shouldBe Note("нет в подписке", Tone.Muted)
    }
})
