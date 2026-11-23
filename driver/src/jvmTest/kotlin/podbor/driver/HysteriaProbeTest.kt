package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import arrow.core.nonEmptyListOf
import io.kotest.matchers.types.shouldBeInstanceOf
import podbor.core.*

class HysteriaProbeTest : FunSpec({
    val server = Server(ServerId("s001"), "UDP", Endpoint("203.0.113.7", 443), Transport.Hysteria2, null, Entry.Unknown, "key")

    test("тишина UDP сохраняется в Ленте без диагноза TCP или TLS") {
        val text = javaClass.getResource("/fixtures/tape/udp-no-response.jsonl")!!.readText().trim()
        val step = rowOf(text).shouldBeInstanceOf<Row.Step>()
        val expected = step.letter.shouldBeInstanceOf<Letter.ProbeAnswered>().verdict
        val prober = ProberImpl(TelegramProbe { 0 }, InstagramProbe { InstagramAnswer(null, 0, null) }, TcpReach { _, _ -> Reach.Up })
        runBlocking {
            listOf(ProbeSpec.Telegram, ProbeSpec.Instagram, ProbeSpec.Https).forEach {
                prober.probe(it, server, 20000) shouldBe expected
            }
        }
        rowOf(rowLine(step)) shouldBe step
    }

    test("ответ через UDP кандидата принимается без TCP соединения к серверу") {
        var tcpCalls = 0
        val prober = ProberImpl(
            TelegramProbe { 3 },
            InstagramProbe { InstagramAnswer(200, 98304, 200) },
            TcpReach { _, _ -> tcpCalls++; Reach.Down },
            https = { true },
        )
        runBlocking {
            prober.probe(ProbeSpec.Telegram, server, 20000) shouldBe Verdict.TelegramDc(3)
            prober.probe(ProbeSpec.Instagram, server, 20000) shouldBe Verdict.Instagram(true, true)
            prober.probe(ProbeSpec.Https, server, 20000) shouldBe Verdict.Https(true)
        }
        tcpCalls shouldBe 0
    }

    test("полная Лента тишины UDP воспроизводится и не хранит выдуманный TCP блок") {
        val prior = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, line(Reach.Up, Reach.Up))))
        val letters = listOf(
            Letter.Pressed(0, OsNetwork.Wifi, listOf(server), null, Memory.Empty, prior),
            Letter.ProbeAnswered(100, server.id, Verdict.NoResponse),
            Letter.ProbeAnswered(200, server.id, Verdict.NoResponse),
        )
        var state: PressState = PressState.Idle
        val rows = mutableListOf<Row>(Row.Run(TAPE_FORMAT, 1, Head.Press()))
        letters.forEach { letter ->
            val (next, deeds) = step(state, letter)
            state = next
            rows += Row.Step(1, letter, deeds)
        }
        state.shouldBeInstanceOf<PressState.Done>()
        val tape = rows.joinToString("\n", transform = ::rowLine)
        replay(tape).single().outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done
        rows.filterIsInstance<Row.Step>().flatMap { it.deeds }.filterIsInstance<Deed.Remember>()
            .all { it.memory.blocks.isEmpty() } shouldBe true
    }
})
