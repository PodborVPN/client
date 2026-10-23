package podbor.driver

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.core.EpochKind
import podbor.core.Instagram
import podbor.core.PROBES_INBOUND
import podbor.core.PULSE_WINDOW_MS
import podbor.core.PairPulse
import podbor.core.Pulse
import podbor.core.PulseSample
import podbor.core.PulseView
import podbor.core.Tally
import podbor.core.Telegram
import podbor.core.Traffic
import podbor.core.TrafficBook

class PulseLoopTest : FunSpec({

    test("sampleOfParsesXrayNames: имена StatsService → входы и выходы; нет половины — ноль") {
        val raw = mapOf(
            "inbound>>>tun>>>traffic>>>uplink" to 10L,
            "inbound>>>tun>>>traffic>>>downlink" to 20L,
            "outbound>>>s001.tg>>>traffic>>>downlink" to 5L,
            "user>>>a@b>>>traffic>>>uplink" to 7L,
            "мусор" to 1L,
        )
        sampleOf(raw, epoch = 4, atMs = 100, monoMs = 50) shouldBe
            PulseSample(4, 100, 50, inbound = mapOf("tun" to Traffic(10, 20)), outbound = mapOf("s001.tg" to Traffic(0, 5)))
    }

    test("essential: `tun`, сумма входов проб под `in.*` и выходы пар; вход API и выходы серверов — мимо") {
        val s = PulseSample(
            1, 0, 0,
            inbound = mapOf("tun" to Traffic(1, 2), "in.s001" to Traffic(3, 40), "in.s002" to Traffic(5, 60), "api" to Traffic(9, 9)),
            outbound = mapOf("pair.tg.s001" to Traffic(1, 2), "pair.insta.s001" to Traffic.Zero, "s001" to Traffic(3, 40)),
        )
        s.essential() shouldBe s.copy(
            inbound = mapOf("tun" to Traffic(1, 2), PROBES_INBOUND to Traffic(8, 100)),
            outbound = mapOf("pair.tg.s001" to Traffic(1, 2), "pair.insta.s001" to Traffic.Zero),
        )
    }

    /**
     * Счётчики Xray сессии: выход пары Телеги и `tun` — каждый опрос +1000 ↓, пока [growing]; пробы `in.s001` растут с ними
     * (десятая часть), `in.s002` стоит; вход `api` растёт от самого опроса, выход сервера — тоже; пара Инсты — ноль.
     */
    class Counters(var growing: Boolean = true, var failing: Boolean = false) : XrayStats {
        var calls = 0
        private var down = 0L
        override suspend fun query(): Either<EngineError, Map<String, Long>> {
            calls++
            if (failing) return EngineError.Failed("QueryStats").left()
            if (growing) down += 1_000
            return mapOf(
                "outbound>>>pair.tg.s001>>>traffic>>>downlink" to down,
                "outbound>>>pair.insta.s001>>>traffic>>>downlink" to 0L,
                "inbound>>>tun>>>traffic>>>downlink" to down,
                "inbound>>>in.s001>>>traffic>>>downlink" to down / 10,
                "inbound>>>in.s002>>>traffic>>>downlink" to 50L,
                "inbound>>>api>>>traffic>>>uplink" to calls * 100L,
                "outbound>>>s001>>>traffic>>>downlink" to calls * 7L,
            ).right()
        }
    }

    val session = PulseContext(EpochKind.Session, network = "wifi", subscription = "sub1")

    /** Петля в фоне теста; [pulse] — свёртка процесса, [taped] — итоги, отданные на сохранение. */
    suspend fun TestScope.loop(
        stats: XrayStats,
        fast: Boolean = true,
        epoch: Long = 1,
        context: PulseContext = session,
        effective: Map<podbor.core.ServiceId, String> = mapOf(Telegram.id to "pair.tg.s001"),
        pulse: MutableStateFlow<Pulse> = MutableStateFlow(Pulse.Empty),
        tape: TapeSink,
        taped: MutableList<TrafficBook> = mutableListOf(),
        views: MutableList<PulseView>,
    ) = launch {
        pulseLoop(
            stats = stats, clock = Clock { 1_000_000 + testScheduler.currentTime }, mono = { testScheduler.currentTime }, epoch = epoch,
            context = { context }, fast = { fast }, effective = { effective }, pulse = pulse, tape = tape,
            onTaped = { taped += it }, emit = { views += it },
        )
    }

    fun loopTest(
        stats: Counters,
        fast: Boolean,
        sink: (MutableList<String>) -> TapeSink = { tape -> TapeSink { tape += it } },
        body: suspend TestScope.(tape: List<String>, views: List<PulseView>) -> Unit,
    ) = runTest {
        val tape = mutableListOf<String>()
        val views = mutableListOf<PulseView>()
        val job = loop(stats, fast = fast, tape = sink(tape), views = views)
        body(tape, views)
        job.cancel()
    }

    test("fastWhileWatched: экран смотрит — опрос раз в секунду, иначе раз в 10 с") {
        val watched = Counters()
        loopTest(watched, fast = true) { _, _ -> runCurrent(); advanceTimeBy(25_000); runCurrent() }
        watched.calls shouldBe 26
        val idle = Counters()
        loopTest(idle, fast = false) { _, _ -> runCurrent(); advanceTimeBy(25_000); runCurrent() }
        idle.calls shouldBe 3
    }

    test("tapeAtMostEvery10sAndOnlyOnChange: в Ленту — не чаще 10 с и только при изменении") {
        val stats = Counters()
        loopTest(stats, fast = true) { tape, views ->
            runCurrent(); advanceTimeBy(25_000); runCurrent()
            tape.map { (rowOf(it) as Row.Pulse).sample.monoMs } shouldBe listOf(0L, 10_000L, 20_000L)
            stats.growing = false
            advanceTimeBy(30_000); runCurrent()
            // Рост за 20…25 с записан один раз (на 30 с); дальше счётчики стоят — строк нет.
            tape.size shouldBe 4
            views.last().pairs[Telegram.id] shouldBe PairPulse.Seen(0, 0, 30_000)
        }
    }

    test("statsFailureLeavesLastPulse: сбой API статистики — опрос идёт дальше; долгий — пара уходит в ⊥, итоги остаются") {
        val stats = Counters()
        loopTest(stats, fast = true) { _, views ->
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            views.last().pairs[Telegram.id] shouldBe PairPulse.Seen(1_000, 0, 0)
            stats.failing = true
            advanceTimeBy(3_000); runCurrent()
            views.last().pairs[Telegram.id] shouldBe PairPulse.Seen(1_000, 0, 3_000)
            advanceTimeBy(2 * PULSE_WINDOW_MS); runCurrent()
            views.last().pairs[Telegram.id] shouldBe PairPulse.Unseen
            views.last().vpn shouldBe Tally("sub1", Traffic(0, 2_000))
            stats.failing = false
            advanceTimeBy(1_000); runCurrent()
            stats.calls shouldBe 2 + 3 + 20 + 1
        }
    }

    test("в Ленту — `tun`, сумма проб и ненулевые выходы пар с ключами итогов; вход API строк не порождает") {
        val stats = Counters()
        loopTest(stats, fast = true) { tape, _ ->
            runCurrent()
            val first = (rowOf(tape.single()) as Row.Pulse).sample
            first.inbound shouldBe mapOf("tun" to Traffic(0, 1_000), PROBES_INBOUND to Traffic(0, 150))
            first.outbound.keys shouldBe setOf("pair.tg.s001")
            (first.kind to first.network to first.subscription) shouldBe (EpochKind.Session to "wifi" to "sub1")
            stats.growing = false
            advanceTimeBy(30_000); runCurrent()
            // Пара, `tun` и пробы стоят с первого опроса; растут только API и выход сервера — строк больше нет.
            tape.size shouldBe 1
        }
    }

    test("пара без трафика — видна с нулём («сейчас не пользуется»), а не ⊥: нули не пишутся только в Ленту") {
        runTest {
            val views = mutableListOf<PulseView>()
            val job = loop(Counters(), effective = mapOf(Instagram.id to "pair.insta.s001"), tape = TapeSink {}, views = views)
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            views.last().pairs[Instagram.id] shouldBe PairPulse.Seen(0, 0, null)
            job.cancel()
        }
    }

    test("Лента не пишется (диск полон) — опрос и экран живут дальше, итоги на сохранение не отдаются") {
        runTest {
            val views = mutableListOf<PulseView>()
            val taped = mutableListOf<TrafficBook>()
            val job = loop(Counters(), tape = TapeSink { error("нет места на диске") }, taped = taped, views = views)
            runCurrent(); advanceTimeBy(3_000); runCurrent()
            views.size shouldBe 4
            taped shouldBe emptyList()
            job.cancel()
        }
    }

    test("сессия, затем анализ — одна свёртка процесса: итог сети копит пробы анализа, итог VPN — нет; итоги — на сохранение") {
        runTest {
            val pulse = MutableStateFlow(Pulse.Empty)
            val taped = mutableListOf<TrafficBook>()
            val views = mutableListOf<PulseView>()
            val first = loop(Counters(), pulse = pulse, tape = TapeSink {}, taped = taped, views = views)
            runCurrent()
            first.cancel()
            val scan = XrayStats { mapOf("inbound>>>in.s003>>>traffic>>>downlink" to 500L, "inbound>>>api>>>traffic>>>uplink" to 9L).right() }
            val second = loop(
                scan, epoch = 2, context = PulseContext(EpochKind.Scan, "wifi", "sub1"), pulse = pulse, tape = TapeSink {}, taped = taped,
                views = views,
            )
            runCurrent()
            second.cancel()
            pulse.value.book shouldBe TrafficBook(network = Tally("wifi", Traffic(0, 1_650)), vpn = Tally("sub1", Traffic(0, 1_000)))
            taped.last() shouldBe pulse.value.book
        }
    }
})
