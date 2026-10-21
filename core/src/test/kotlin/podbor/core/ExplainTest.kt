package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

class ExplainTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)
    /** Белые списки — два раунда «похоже» подряд; один — только подозрение. */
    val confirmed = arrayOf<Letter>(Letter.LineObserved(10, whitelist), Letter.LineObserved(20, whitelist))
    val open = line(Reach.Up, Reach.Up)

    fun pressed(servers: List<Server>, memory: Memory = Memory.Empty) =
        Letter.Pressed(atMs = 0, os = mts, servers = servers, currentExit = null, memory = memory)

    fun answer(tag: String, v: Verdict, at: Long = 100) = Letter.ProbeAnswered(at, ServerId(tag), v)

    test("пример: 7 входов тишина, 3 не отвечают, 1 без Телеги, 81 зарубежный (через мир с повторами)") {
        val chains = (1..7).map { server("s%03d".format(it), Entry.Ru) }
        val singles = (8..11).map { server("s%03d".format(it), Entry.Ru) }
        val foreign = (12..92).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val verdicts = chains.associate { it.id to (Verdict.TlsSilence as Verdict) } +
            singles.take(3).associate { it.id to Verdict.Unreachable } + mapOf(ServerId("s011") to Verdict.TelegramDc(1))
        val run = runPress(pressed(chains + singles + foreign), FakeWorld(whitelist, verdicts))
        val e = explain(run.letters)
        e.diagnosis.scenario shouldBe Scenario.MobileWhitelist
        e.lineEvidence shouldBe whitelist
        e.tried shouldBe mapOf(
            CandidateClass.RuEntry to mapOf(VerdictKind.TlsSilence to 7, VerdictKind.Unreachable to 3, VerdictKind.ServiceDead to 1),
        )
        e.skipped shouldBe mapOf(CandidateClass.Foreign to mapOf(SkipReason.ForeignInWhitelist to 81))
        e.outcome shouldBe Outcome.NotFound(Conclusion.AllAdmissibleFailed)
    }

    test("подписка без Ru при белых списках → NoAdmissibleInConfig, tried пуст") {
        val e = explain(listOf(pressed(listOf(server("s001", Entry.Foreign("NL")))), *confirmed))
        e.tried.shouldBeEmpty()
        e.outcome shouldBe Outcome.NotFound(Conclusion.NoAdmissibleInConfig)
    }

    test("пустая подписка → NoAdmissibleInConfig") {
        explain(listOf(pressed(emptyList()), Letter.LineObserved(10, open))).outcome shouldBe
            Outcome.NotFound(Conclusion.NoAdmissibleInConfig)
    }

    test("чистая сеть, все упали → NothingWorksOpen") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        explain(
            listOf(pressed(servers), Letter.LineObserved(10, open), answer("s001", Verdict.Unreachable), answer("s002", Verdict.TlsSilence),
                answer("s002", Verdict.TlsSilence, at = 900)),
        ).outcome shouldBe Outcome.NotFound(Conclusion.NothingWorksOpen)
    }

    test("нет сети → NoNetwork") {
        explain(listOf(pressed(emptyList()), Letter.LineObserved(10, line(Reach.Down, Reach.Down)))).outcome shouldBe
            Outcome.NotFound(Conclusion.NoNetwork)
    }

    test("Found: восьмой ответивший из 11 → position 8, admissible 11") {
        val servers = (1..11).map { server("s%03d".format(it), Entry.Ru) }
        val answers = (1..7).map { answer("s%03d".format(it), Verdict.Unreachable) } + answer("s008", Verdict.TelegramDc(3))
        explain(listOf(pressed(servers), *confirmed) + answers).outcome shouldBe
            Outcome.Found(ServerId("s008"), position = 8, admissible = 11)
    }

    test("нет LineObserved → InProgress") {
        explain(listOf(pressed(listOf(server("s001", Entry.Ru))))).outcome shouldBe Outcome.InProgress
    }

    test("двойники на одном IP:порт после второй тишины: нажатие кончается, объяснение не застревает в InProgress") {
        val same = (1..6).map { server("s%03d".format(it), Entry.Ru, host = "10.9.9.1", port = 8443) }
        val run = runPress(pressed(same), FakeWorld(whitelist, mapOf(ServerId("s001") to Verdict.TlsSilence)))
        explain(run.letters).outcome shouldBe Outcome.NotFound(Conclusion.AllAdmissibleFailed)
    }

    test("свойство: explain не падает и согласован с машиной — InProgress ⟺ нажатие не закончено") {
        val entries = Arb.choice(Arb.int(1..4).map { Entry.Ru as Entry }, Arb.int(5..8).map { Entry.Foreign("NL") }, Arb.int(0..0).map { Entry.Unknown })
        val verdicts = Arb.choice(
            Arb.int(0..0).map { Verdict.Unreachable as Verdict }, Arb.int(0..0).map { Verdict.TlsSilence },
            Arb.int(0..5).map { Verdict.TelegramDc(it) },
        )
        val letter = Arb.choice(
            Arb.int(1..10).map { n -> Letter.LineObserved(n.toLong(), line(if (n % 2 == 0) Reach.Up else Reach.Down, if (n % 3 == 0) Reach.Up else Reach.Down)) as Letter },
            Arb.int(1..10).map { n -> answer("s%03d".format(n), Verdict.Unreachable, n.toLong()) },
        )
        checkAll(Arb.list(entries, 0..8), Arb.list(letter, 0..20), Arb.list(verdicts, 1..3)) { es, ls, vs ->
            val servers = es.mapIndexed { i, e -> server("s%03d".format(i + 1), e, host = "10.0.${i % 2}.${i + 1}", port = 443 + i % 3) }
            val letters = listOf(pressed(servers)) + ls.mapIndexed { i, l ->
                if (l is Letter.ProbeAnswered) l.copy(verdict = vs[i % vs.size]) else l
            }
            val e = explain(letters)
            val machine = letters.fold(PressState.Idle as PressState) { s, l -> step(s, l).first }
            (e.outcome == Outcome.InProgress) shouldBe (machine !is PressState.Done)
            val answered = letters.filterIsInstance<Letter.ProbeAnswered>().map { it.server }.toSet().count { id -> servers.any { it.id == id } }
            (e.tried.values.sumOf { it.values.sum() } <= answered) shouldBe true
        }
    }
})
