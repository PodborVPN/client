package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PulseTest : FunSpec({

    val tg = mapOf(Telegram.id to "s001.tg")

    /** Отсчёт: накопленные байты выхода пары `s001.tg` и входа `tun` на момент [mono]. */
    fun at(mono: Long, down: Long, up: Long = 0, epoch: Long = 1, tun: Traffic = Traffic(up, down), tag: String = "s001.tg") =
        PulseSample(epoch, atMs = 1_000_000 + mono, monoMs = mono, inbound = mapOf("tun" to tun), outbound = mapOf(tag to Traffic(up, down)))

    fun fold(vararg s: PulseSample) = s.fold(Pulse.Empty) { p, x -> p.after(x) }

    test("rateOverWindow: 0 → 50 000 Б вниз за 10 с — 5000 Б/с") {
        fold(at(0, 0), at(10_000, 50_000)).view(tg, 10_000).pairs[Telegram.id] shouldBe PairPulse.Seen(5_000, 0, 0)
    }

    test("idleIsSeenWithZero: счётчики стоят — пара видна, темп ноль, ответа в сессии не было") {
        fold(at(0, 700), at(1_000, 700)).view(tg, 1_000).pairs[Telegram.id] shouldBe PairPulse.Seen(0, 0, null)
    }

    test("unseenWithoutSamples: нет отсчётов или один — ⊥, а не ноль") {
        Pulse.Empty.view(tg, 0).pairs[Telegram.id] shouldBe PairPulse.Unseen
        fold(at(0, 700)).view(tg, 0).pairs[Telegram.id] shouldBe PairPulse.Unseen
        Pulse.Empty.view(tg, 0).session shouldBe null
    }

    test("lostSamplesKeepBytes: пропуск опроса теряет разрешение, не байты") {
        // Опрос раз в 10 с вместо секунды: база темпа — последний отсчёт до окна.
        fold(at(0, 0), at(20_000, 100_000)).view(tg, 20_000).pairs[Telegram.id] shouldBe PairPulse.Seen(5_000, 0, 0)
        fold(at(0, 0), at(5_000, 30_000), at(20_000, 100_000)).view(tg, 20_000).pairs[Telegram.id] shouldBe
            PairPulse.Seen(70_000 * 1_000 / 15_000, 0, 0)
    }

    test("epochChangeKeepsSessionTotal: Xray перезапустился — итог сессии — сумма эпох, темп не в минус") {
        val p = fold(
            at(0, 0, tun = Traffic(0, 0)),
            at(1_000, 9_000, tun = Traffic(100, 9_000)),
            at(2_000, 0, epoch = 2, tun = Traffic(0, 0)),
            at(3_000, 500, epoch = 2, tun = Traffic(20, 500)),
        )
        p.view(tg, 3_000).session shouldBe Traffic(120, 9_500)
        p.view(tg, 3_000).pairs[Telegram.id] shouldBe PairPulse.Seen(500, 0, 0)
    }

    test("decreaseIsUnknown: счётчик убыл в той же эпохе — ⊥, не отрицательный темп; итог — нижняя граница") {
        val p = fold(at(0, 9_000, tun = Traffic(0, 9_000)), at(1_000, 100, tun = Traffic(0, 100)))
        p.view(tg, 1_000).pairs[Telegram.id] shouldBe PairPulse.Unseen
        p.view(tg, 1_000).session shouldBe Traffic(0, 9_100)
    }

    test("newPairStartsFresh: сервис сменил сервер — темп новой пары с её отсчётов, не разность чужих") {
        val p = fold(at(0, 0), at(1_000, 5_000), at(2_000, 80_000, tag = "s002.tg"), at(3_000, 81_000, tag = "s002.tg"))
        p.view(mapOf(Telegram.id to "s002.tg"), 3_000).pairs[Telegram.id] shouldBe PairPulse.Seen(1_000, 0, 0)
    }

    test("answeredAgoIsLastDownGrowth: «ответ N с назад» — от последнего роста ↓, рост ↑ его не двигает") {
        val p = fold(at(0, 0), at(1_000, 400), at(2_000, 400, up = 50), at(3_000, 400, up = 90))
        p.view(tg, 4_000).pairs[Telegram.id] shouldBe PairPulse.Seen(400 * 1_000 / 3_000, 90 * 1_000 / 3_000, 3_000)
    }

    test("отсчёт старше двух окон — темпа нет: опрос встал, вчерашняя скорость — не скорость") {
        fold(at(0, 0), at(1_000, 5_000)).view(tg, 1_000 + 2 * PULSE_WINDOW_MS + 1).pairs[Telegram.id] shouldBe PairPulse.Unseen
    }
})
