package podbor.driver

import arrow.core.Either
import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import podbor.core.Deed
import podbor.core.Instagram
import podbor.core.Telegram
import podbor.core.OsNetwork
import podbor.core.Reach
import podbor.core.Traffic

class ReplayTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)

    /** Лента нажатия на фейках: белые списки, первый RU-сервер подходит. */
    fun pressTape(): List<String> {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = setOf(20000))
        w.pressNow("подписка", mts, currentExit = null)
        return w.tape.toList()
    }

    test("Лента боя на фейках сходится: детектор до Xray, открытие с его раундами и нажатие — три прогона, все Done") {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = setOf(20000))
        runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                val detected = detectLine(w.lineProbe, Clock { System.currentTimeMillis() }, { l -> synchronized(w.tape) { w.tape += l } }, mts, w.services)
                val opening = d.open(warm, w.services, mts, prior = detected.prior)
                d.press(warm, w.services, mts, prior = opening.prior)
            }
        }
        val runs = replay(w.tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Open", "Open", "Press")
        runs.forEach { it.outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done }
    }

    test("подменённые дела на третьей букве → Diverged(step = 2, line = её строка)") {
        val tape = pressTape().toMutableList()
        val third = tape.indices.filter { rowOf(tape[it]) is Row.Step }[2]
        val step = rowOf(tape[third]).shouldBeInstanceOf<Row.Step>()
        tape[third] = rowLine(step.copy(deeds = step.deeds + Deed.ProbeLine))
        val diverged = replay(tape.joinToString("\n")).single().outcome.shouldBeInstanceOf<Replayed.Diverged>()
        diverged.step shouldBe 2
        diverged.line shouldBe third + 1
        diverged.recorded shouldBe step.deeds + Deed.ProbeLine
        diverged.replayed shouldBe step.deeds
    }

    test("срок вышел → Converged(ending = Closed)") {
        val press = pressTape()
        val tape = press.take(2) + rowLine(Row.Closed(rowOf(press.first()).shouldBeInstanceOf<Row.Run>().id))
        replay(tape.joinToString("\n")).single().outcome shouldBe Replayed.Converged(steps = 1, ending = Ending.Closed)
    }

    test("пустой файл → прогонов нет") {
        replay("") shouldBe emptyList()
        replay("\n\n") shouldBe emptyList()
    }

    test("мусорная строка — отдельная запись Unplayable(line = её номер); прогоны вокруг судятся") {
        val press = pressTape()
        val tape = press.take(2) + "мусор" + press.drop(2) + pressTape()
        val runs = replay(tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Press", "?", "Press")
        runs[1].outcome shouldBe Replayed.Unplayable(3, runs[1].outcome.shouldBeInstanceOf<Replayed.Unplayable>().why)
        runs[0].outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done
        runs[2].outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done
    }

    test("оборванная последняя строка («Поделиться» читал живую ленту): прогон судится по целым строкам, обрыв — отдельно") {
        val press = pressTape()
        val tape = press.take(3) + press[3].take(press[3].length / 2)
        val runs = replay(tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Press", "?")
        runs[0].outcome shouldBe Replayed.Converged(steps = 2, ending = Ending.Torn)
        runs[1].outcome.shouldBeInstanceOf<Replayed.Unplayable>().line shouldBe 4
    }

    test("строки двух прогонов вперемешку (детектор досылает раунд во время нажатия) — каждый судится своим id") {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = setOf(20000))
        val detect = mutableListOf<String>()
        runBlocking { detectLine(w.lineProbe, Clock { System.currentTimeMillis() }, { detect += it }, mts, w.services) }
        val press = pressTape()
        // Шапка детектора и его первая буква, затем нажатие начинается, затем детектор досылает остальное.
        val tape = detect.take(2) + press.take(3) + detect.drop(2) + press.drop(3)
        val runs = replay(tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Open", "Press")
        runs.forEach { it.outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done }
    }

    test("Лента v1 (до трубопроводов) проигрывается как трубопровод Телеги без расхождений") {
        val runs = replay(resource("fixtures/tape/v1-open-press.jsonl"))
        runs.map { it.machine } shouldBe listOf("Open", "Open", "Press")
        runs.map { it.service } shouldBe List(3) { Telegram.id }
        runs.forEach { it.outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done }
    }

    test("Лента v2 двух трубопроводов: каждый прогон проигрывается своим сервисом без расхождений") {
        val tape = pipesTape()
        tape.map(::rowOf).filterIsInstance<Row.Run>().map { it.v } shouldBe listOf(TAPE_FORMAT, TAPE_FORMAT)
        val runs = replay(tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Press", "Press")
        runs.mapNotNull { it.service }.toSet() shouldBe setOf(Telegram.id, Instagram.id)
        runs.forEach { it.outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done }
    }

    test("Лента v1, анализ и Лента v2 подряд: v1/v2 — как прежде, анализ — прогон Scan, все сходятся до Done") {
        val tape = resource("fixtures/tape/v1-open-press.jsonl").lines().filter { it.isNotBlank() } + scanTape() + pipesTape()
        val runs = replay(tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Open", "Open", "Press", "Scan", "Press", "Press")
        runs.map { it.service } shouldBe List(3) { Telegram.id } + null + runs.drop(4).map { it.service }
        runs.drop(4).mapNotNull { it.service }.toSet() shouldBe setOf(Telegram.id, Instagram.id)
        runs.forEach { it.outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done }
    }

    test("Лента v0 → один RunReplay v0, Unplayable") {
        val runs = replay(resource("fixtures/tape/v0-open.jsonl"))
        runs.single().machine shouldBe "v0"
        runs.single().outcome.shouldBeInstanceOf<Replayed.Unplayable>().line shouldBe 1
    }

    test("строки пульса Ленты v2 читаются и прогонам не мешают: те же прогоны, что без них") {
        val withPulse = resource("fixtures/tape/v2-pulse.jsonl")
        val pulses = withPulse.lines().filter { it.isNotBlank() }.map(::rowOf).filterIsInstance<Row.Pulse>()
        pulses.map { it.sample.outbound["pair.tg.s001"] } shouldBe listOf(Traffic(823, 5_399), Traffic(1_823, 95_399))
        replay(withPulse).map { it.machine to it.outcome } shouldBe
            replay(resource("fixtures/tape/v1-open-press.jsonl")).map { it.machine to it.outcome }
    }
})
