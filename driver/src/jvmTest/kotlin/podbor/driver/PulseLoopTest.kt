package podbor.driver

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.core.PairPulse
import podbor.core.PulseSample
import podbor.core.PulseView
import podbor.core.Telegram
import podbor.core.Traffic

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

    /** Счётчик выхода Телеги: каждый опрос +1000 ↓, пока [growing]. */
    class Counters(var growing: Boolean = true, var failing: Boolean = false) : XrayStats {
        var calls = 0
        private var down = 0L
        override suspend fun query(): Either<EngineError, Map<String, Long>> {
            calls++
            if (failing) return EngineError.Failed("QueryStats").left()
            if (growing) down += 1_000
            return mapOf("outbound>>>s001.tg>>>traffic>>>downlink" to down, "inbound>>>tun>>>traffic>>>downlink" to down).right()
        }
    }

    fun loopTest(stats: Counters, fast: Boolean, body: suspend kotlinx.coroutines.test.TestScope.(tape: List<String>, views: List<PulseView>) -> Unit) = runTest {
        val tape = mutableListOf<String>()
        val views = mutableListOf<PulseView>()
        val job = launch {
            pulseLoop(
                stats = stats, clock = Clock { 1_000_000 + testScheduler.currentTime }, mono = { testScheduler.currentTime }, epoch = 1,
                fast = { fast }, effective = { mapOf(Telegram.id to "s001.tg") }, tape = TapeSink { tape += it }, emit = { views += it },
            )
        }
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

    test("statsFailureLeavesLastPulse: API статистики ответил ошибкой — прежний пульс остаётся, опрос идёт дальше") {
        val stats = Counters()
        loopTest(stats, fast = true) { _, views ->
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            views.size shouldBe 2
            stats.failing = true
            advanceTimeBy(3_000); runCurrent()
            views.size shouldBe 2
            stats.failing = false
            advanceTimeBy(1_000); runCurrent()
            views.size shouldBe 3
        }
    }
})
