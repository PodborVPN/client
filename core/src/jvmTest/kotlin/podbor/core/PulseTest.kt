package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PulseTest : FunSpec({

    val tg = mapOf(Telegram.id to "s001.tg")

    /**
     * Отсчёт: накопленные байты выхода пары `s001.tg`, входа `tun` и суммы проб на момент [mono]; эпоха [kind] на сети
     * [network] и подписке [sub].
     */
    fun at(
        mono: Long, down: Long, up: Long = 0, epoch: Long = 1, tun: Traffic = Traffic(up, down), tag: String = "s001.tg",
        probes: Traffic = Traffic.Zero, kind: EpochKind = EpochKind.Session, network: String = "wifi", sub: String = "sub1",
    ) = PulseSample(
        epoch, atMs = 1_000_000 + mono, monoMs = mono,
        inbound = mapOf(TUN_INBOUND to tun, "in.*" to probes), outbound = mapOf(tag to Traffic(up, down)),
        kind = kind, network = network, subscription = sub,
    )

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
        Pulse.Empty.view(tg, 0).network shouldBe null
        Pulse.Empty.view(tg, 0).vpn shouldBe null
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
        p.view(tg, 3_000).vpn shouldBe Tally("sub1", Traffic(120, 9_500))
        p.view(tg, 3_000).pairs[Telegram.id] shouldBe PairPulse.Seen(500, 0, 0)
    }

    test("decreaseIsUnknown: счётчик убыл в той же эпохе — ⊥, не отрицательный темп; итог — нижняя граница") {
        val p = fold(at(0, 9_000, tun = Traffic(0, 9_000)), at(1_000, 100, tun = Traffic(0, 100)))
        p.view(tg, 1_000).pairs[Telegram.id] shouldBe PairPulse.Unseen
        p.view(tg, 1_000).vpn shouldBe Tally("sub1", Traffic(0, 9_100))
    }

    test("newPairStartsFresh: сервис сменил сервер — темп новой пары с её отсчётов, не разность чужих") {
        val p = fold(at(0, 0), at(1_000, 5_000), at(2_000, 80_000, tag = "s002.tg"), at(3_000, 81_000, tag = "s002.tg"))
        p.view(mapOf(Telegram.id to "s002.tg"), 3_000).pairs[Telegram.id] shouldBe PairPulse.Seen(1_000, 0, 0)
    }

    test("answeredAgoIsLastDownGrowth: «ответ N с назад» — от последнего роста ↓, рост ↑ его не двигает") {
        val p = fold(at(0, 0), at(1_000, 400), at(2_000, 400, up = 50), at(3_000, 400, up = 90))
        p.view(tg, 4_000).pairs[Telegram.id] shouldBe PairPulse.Seen(400 * 1_000 / 3_000, 90 * 1_000 / 3_000, 3_000)
    }

    test("tallyAcrossEpochsAndKinds: сеть — приложения + пробы по всем эпохам, VPN — только приложения") {
        val p = fold(
            at(0, 0),
            at(1_000, 9_000, probes = Traffic(100, 400)),
            at(2_000, 0, epoch = 2, tun = Traffic.Zero, kind = EpochKind.Scan),
            at(3_000, 0, epoch = 2, tun = Traffic.Zero, probes = Traffic(1_000, 2_000), kind = EpochKind.Scan),
        )
        p.view(tg, 3_000).network shouldBe Tally("wifi", Traffic(1_100, 11_400))
        p.view(tg, 3_000).vpn shouldBe Tally("sub1", Traffic(0, 9_000))
    }

    test("networkChangeStartsNetworkTally: другая сеть — её итог с нуля, итог подписки копится дальше") {
        val p = fold(at(0, 0), at(1_000, 9_000), at(2_000, 10_000, network = "cell:25001"))
        p.view(tg, 2_000).network shouldBe Tally("cell:25001", Traffic(0, 1_000))
        p.view(tg, 2_000).vpn shouldBe Tally("sub1", Traffic(0, 10_000))
    }

    test("входы проб — каждый своей меткой: вход пропал из отсчёта — второго счёта проб эпохи нет") {
        fun probes(mono: Long, vararg ins: Pair<String, Long>) =
            PulseSample(1, mono, mono, inbound = ins.associate { (k, d) -> k to Traffic(0, d) }, outbound = emptyMap(), network = "wifi")
        val p = fold(
            probes(0, "in.s001" to 100, "in.s002" to 200),
            probes(1_000, "in.s001" to 150),
            probes(2_000, "in.s001" to 150, "in.s002" to 250),
        )
        p.book.network shouldBe Tally("wifi", Traffic(0, 400))
    }

    test("сеть неизвестна (ключ пуст) — итог сети не стирается: прирост копится в прежний, а без прежнего — никуда") {
        val p = fold(at(0, 0), at(1_000, 9_000), at(2_000, 10_000, network = ""), at(3_000, 12_000))
        p.view(tg, 3_000).network shouldBe Tally("wifi", Traffic(0, 12_000))
        fold(at(0, 0, network = ""), at(1_000, 5_000, network = "")).view(tg, 1_000).network shouldBe null
    }

    test("subscriptionChangeStartsVpnTallyEvenWithoutTraffic: анализ новой подписки сбрасывает итог VPN и без трафика") {
        val p = fold(at(0, 0), at(1_000, 9_000), at(2_000, 0, epoch = 2, tun = Traffic.Zero, kind = EpochKind.Scan, sub = "sub2"))
        p.view(tg, 2_000).vpn shouldBe Tally("sub2", Traffic.Zero)
        p.view(tg, 2_000).network shouldBe Tally("wifi", Traffic(0, 9_000))
    }

    test("resumedKeepsStoredBook: итоги, сохранённые прошлым процессом, копятся дальше") {
        val stored = TrafficBook(network = Tally("wifi", Traffic(5, 50_000)), vpn = Tally("sub1", Traffic(5, 40_000)))
        val p = listOf(at(0, 0), at(1_000, 1_000)).fold(Pulse.resumed(stored)) { acc, x -> acc.after(x) }
        p.book shouldBe TrafficBook(network = Tally("wifi", Traffic(5, 51_000)), vpn = Tally("sub1", Traffic(5, 41_000)))
    }

    test("networkKeyOf: Wi-Fi — одна сеть, сотовая — по MCC MNC, нет сети — нет ключа") {
        networkKey(OsNetwork.Wifi) shouldBe "wifi"
        networkKey(OsNetwork.Cellular("MTS", "25001")) shouldBe "cell:25001"
        // Вышку ключ не знает: лёгкое чтение сети (без вышки) и полное дают один ключ.
        networkKey(OsNetwork.Cellular("MTS", "25001", Tower.Known(1575, 1300, -103))) shouldBe "cell:25001"
        networkKey(OsNetwork.None) shouldBe null
    }

    test("отсчёт старше двух окон — темпа нет: опрос встал, вчерашняя скорость — не скорость") {
        fold(at(0, 0), at(1_000, 5_000)).view(tg, 1_000 + 2 * PULSE_WINDOW_MS + 1).pairs[Telegram.id] shouldBe PairPulse.Unseen
    }
})
