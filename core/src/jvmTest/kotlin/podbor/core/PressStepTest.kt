package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Переходы машины нажатия по одному. */
class PressStepTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)
    /** Белые списки — два раунда «похоже» подряд; один — только подозрение. */
    val confirmed = arrayOf<Letter>(Letter.LineObserved(10, whitelist), Letter.LineObserved(20, whitelist))
    val open = line(Reach.Up, Reach.Up)

    fun pressed(servers: List<Server>, exit: ServerId? = null, memory: Memory = Memory.Empty, at: Long = 0, service: ServiceId = Telegram.id, pinned: Boolean = true) =
        Letter.Pressed(atMs = at, os = mts, servers = servers, currentExit = exit, memory = memory, service = service, pinned = pinned)

    /** Прогнать буквы с Idle, вернуть последнее состояние и дела последнего шага. */
    fun run(vararg letters: Letter): Pair<PressState, List<Deed>> =
        letters.fold(PressState.Idle as PressState to emptyList<Deed>()) { (s, _), l -> step(s, l) }

    fun probes(deeds: List<Deed>) = deeds.filterIsInstance<Deed.Probe>().map { it.server.tag }

    val ru = (1..5).map { server("s%03d".format(it), Entry.Ru) }
    val foreign = (6..8).map { server("s%03d".format(it), Entry.Foreign("NL")) }

    test("Idle + Pressed без выхода → ProbeLine") {
        run(pressed(ru)).second shouldContainExactly listOf(Deed.ProbeLine)
    }

    test("Pressed с выходом → ProbeLine и Probe(выход)") {
        run(pressed(ru, exit = ServerId("s002"))).second shouldContainExactly
            listOf(Deed.ProbeLine, Deed.Probe(ServerId("s002")))
    }

    test("повторное нажатие: линия раньше ответа выхода → подбор ждёт выход («сначала перепроверяет»)") {
        val (s1, d1) = run(pressed(ru, exit = ServerId("s002")), *confirmed)
        probes(d1).shouldBeEmpty()
        val (s2, d2) = step(s1, Letter.ProbeAnswered(900, ServerId("s002"), Verdict.Unreachable))
        probes(d2).isNotEmpty() shouldBe true
        s2.shouldBeInstanceOf<PressState.Running>()
    }

    test("первый раунд «похоже» → сразу Probe только по Ru (годны при обеих гипотезах) и подтверждающий ProbeLine") {
        val (s, d) = run(pressed(foreign + ru), Letter.LineObserved(10, whitelist))
        probes(d) shouldContainExactly listOf("s001", "s002", "s003", "s004", "s005")
        d shouldContain Deed.ProbeLine
        s.shouldBeInstanceOf<PressState.Running>().diagnosis.scene shouldBe Scene.Chosen(Scenario.SuspectWhitelist)
    }

    test("подтверждение белых списков посреди перебора: зарубежные так и не трогаем") {
        val (s, d) = run(pressed(foreign + ru), *confirmed)
        probes(d).shouldBeEmpty()
        s.shouldBeInstanceOf<PressState.Running>().diagnosis.scene shouldBe Scene.Chosen(Scenario.MobileWhitelist)
    }

    test("подозрение, затем открыто: зарубежные встают в очередь") {
        val (s, d) = run(pressed(ru.take(1) + foreign), Letter.LineObserved(10, whitelist), Letter.LineObserved(20, open))
        s.shouldBeInstanceOf<PressState.Running>().diagnosis.scene shouldBe Scene.Chosen(Scenario.MobileOpen)
        probes(d) shouldContainAll listOf("s006", "s007", "s008")
    }

    test("подозрение без входов в РФ — не тупик, ждём подтверждения") {
        val (s, d) = run(pressed(foreign), Letter.LineObserved(10, whitelist))
        s.shouldBeInstanceOf<PressState.Running>().diagnosis.shouldBeInstanceOf<Diagnosis.Suspected>()
        d shouldContainExactly listOf(Deed.ProbeLine)
    }

    test("подозрение без серверов кончается после второго раунда") {
        val (s1, d1) = run(pressed(emptyList()), Letter.LineObserved(10, whitelist))
        s1.shouldBeInstanceOf<PressState.Running>()
        d1 shouldContainExactly listOf(Deed.ProbeLine)
        val (s2, _) = step(s1, Letter.LineObserved(20, whitelist))
        val done = s2.shouldBeInstanceOf<PressState.Done>()
        explain(done.letters).outcome shouldBe Outcome.NotFound(Conclusion.NoAdmissibleInConfig)
    }

    test("Fits → Done, MakeExit, Remember с успехом, MeasureSpeed") {
        val (s, d) = run(
            pressed(ru), *confirmed,
            Letter.ProbeAnswered(500, ServerId("s002"), Verdict.TelegramDc(3)),
        )
        s.shouldBeInstanceOf<PressState.Done>()
        d shouldContain Deed.MakeExit(ServerId("s002"))
        d shouldContain Deed.MeasureSpeed(ServerId("s002"))
        val remember = d.filterIsInstance<Deed.Remember>().single()
        remember.memory.successes[MemoryKey("MTS", Scenario.MobileWhitelist)]!!.first().serverKey shouldBe ru[1].key
    }

    test("нажатие по Инсте: успех под ключом insta, ключ Телеги не тронут") {
        val (_, d) = run(
            pressed(ru, service = Instagram.id), *confirmed,
            Letter.ProbeAnswered(500, ServerId("s002"), Verdict.Instagram(cdnOk = true, wwwOk = true)),
        )
        val successes = d.filterIsInstance<Deed.Remember>().single().memory.successes
        successes[MemoryKey("MTS", Scenario.MobileWhitelist, Instagram.id)]!!.first().serverKey shouldBe ru[1].key
        successes[MemoryKey("MTS", Scenario.MobileWhitelist, Telegram.id)] shouldBe null
    }

    test("найден currentExit при pinned = false → MakeExit; при pinned = true — нет") {
        val answer = Letter.ProbeAnswered(400, ServerId("s003"), Verdict.TelegramDc(5))
        run(pressed(ru, exit = ServerId("s003"), pinned = false), answer).second shouldContain Deed.MakeExit(ServerId("s003"))
        run(pressed(ru, exit = ServerId("s003"), pinned = true), answer).second.filterIsInstance<Deed.MakeExit>().shouldBeEmpty()
    }

    test("первая тишина — повтор в конце очереди без блока; вторая — блок IP:порт") {
        val a = server("s001", Entry.Ru, host = "10.9.9.1", port = 8443)
        val (s1, d1) = run(pressed(listOf(a)), *confirmed, Letter.ProbeAnswered(300, a.id, Verdict.TlsSilence))
        probes(d1) shouldContainExactly listOf("s001")
        s1.shouldBeInstanceOf<PressState.Running>().memory.isBlocked(a.endpoint, nowMs = 301) shouldBe false
        val (s2, _) = step(s1, Letter.ProbeAnswered(900, a.id, Verdict.TlsSilence))
        s2.shouldBeInstanceOf<PressState.Done>()
        explain(s2.letters).tried shouldBe mapOf(CandidateClass.RuEntry to mapOf(VerdictKind.TlsSilence to 1))
    }

    test("ServiceDead — тоже повтор; Unreachable — без повтора") {
        val a = server("s001", Entry.Ru)
        val b = server("s002", Entry.Ru)
        val (_, d1) = run(pressed(listOf(a)), *confirmed, Letter.ProbeAnswered(300, a.id, Verdict.TelegramDc(1)))
        probes(d1) shouldContainExactly listOf("s001")
        val (s2, d2) = run(pressed(listOf(b)), *confirmed, Letter.ProbeAnswered(300, b.id, Verdict.Unreachable))
        probes(d2).shouldBeEmpty()
        s2.shouldBeInstanceOf<PressState.Done>()
    }

    test("4 провала на 4 разных IP → ProbeLine, новых проб нет до LineObserved") {
        val servers = (1..8).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val answers = (1..4).map { Letter.ProbeAnswered(100L + it, ServerId("s%03d".format(it)), Verdict.Unreachable) }
        val (s, d) = run(pressed(servers), Letter.LineObserved(10, open), *answers.toTypedArray())
        d shouldContainExactly listOf(Deed.ProbeLine)
        s.shouldBeInstanceOf<PressState.Running>().diagnosis.shouldBeInstanceOf<Diagnosis.Rechecking>()
    }

    test("после перепроверки подтверждение судится только по новым раундам: старый «похоже» не в счёт") {
        val servers = (1..8).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val answers = (1..4).map { Letter.ProbeAnswered(100L + it, ServerId("s%03d".format(it)), Verdict.Unreachable) }
        // Старый диагноз: подозрение, затем открыто — «похоже» в нём есть.
        val before = arrayOf(pressed(servers), Letter.LineObserved(10, whitelist), Letter.LineObserved(20, open), *answers.toTypedArray())
        run(*before).first.shouldBeInstanceOf<PressState.Running>().diagnosis.shouldBeInstanceOf<Diagnosis.Rechecking>()
        // Новый раунд «похоже» (другой по виду) — только подозрение и подтверждающий раунд, не белые списки.
        val fresh = whitelist.copy(abroad = whitelist.abroad.map { it.copy(handshake = Handshake.Reset(Stage.Tls)) })
        val (s1, d1) = run(*before, Letter.LineObserved(200, fresh))
        s1.shouldBeInstanceOf<PressState.Running>().diagnosis shouldBe Diagnosis.Suspected(fresh)
        d1 shouldContain Deed.ProbeLine
        val (s2, _) = step(s1, Letter.LineObserved(300, open))
        s2.shouldBeInstanceOf<PressState.Running>().diagnosis shouldBe Diagnosis.Settled(Scenario.MobileOpen, nonEmptyListOf(fresh, open))
    }

    test("Fits в окне перепроверки: успех под прежним сценарием, честный охват и прежняя сеть на экране") {
        val servers = (1..8).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val answers = (1..4).map { Letter.ProbeAnswered(100L + it, ServerId("s%03d".format(it)), Verdict.Unreachable) }
        val (s, d) = run(pressed(servers), Letter.LineObserved(10, open), *answers.toTypedArray(),
            Letter.ProbeAnswered(150, ServerId("s005"), Verdict.TelegramDc(2)))
        val done = s.shouldBeInstanceOf<PressState.Done>()
        val remember = d.filterIsInstance<Deed.Remember>().single()
        remember.memory.successes[MemoryKey("MTS", Scenario.MobileOpen)]!!.first().serverKey shouldBe servers[4].key
        val screen = render(done.letters)
        screen.network.shouldBeInstanceOf<NetworkCard.Measured>().scenario shouldBe Scenario.MobileOpen
        screen.result.shouldBeInstanceOf<ResultCard.Found>().coverage shouldBe "проверено 5 из 8 серверов"
    }

    test("белые списки: 4 провала Ru на разных IP не ставят перебор на паузу ради линии") {
        val servers = (1..10).map { server("s%03d".format(it), Entry.Ru) }
        val answers = (1..4).map { Letter.ProbeAnswered(100L + it, ServerId("s%03d".format(it)), Verdict.Unreachable) }
        val (_, d) = run(pressed(servers), *confirmed, *answers.toTypedArray())
        d shouldNotContain Deed.ProbeLine
        probes(d) shouldContainExactly listOf("s010")
    }

    test("перепроверка линии не теряет отложенные повторы (4 холодные тишины → линия → повторы идут)") {
        val servers = (1..4).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val answers = (1..4).map { Letter.ProbeAnswered(100L + it, ServerId("s%03d".format(it)), Verdict.TlsSilence) }
        val (_, d1) = run(pressed(servers), Letter.LineObserved(10, open), *answers.toTypedArray())
        d1 shouldContainExactly listOf(Deed.ProbeLine)
        val (_, d) = run(pressed(servers), Letter.LineObserved(10, open), *answers.toTypedArray(), Letter.LineObserved(200, open))
        // s001–s003 перезапущены сразу после своей тишины; отложенным остался s004 — он и идёт после линии.
        probes(d) shouldContainExactly listOf("s004")
    }

    test("третьей перепроверки нет") {
        val servers = (1..20).map { server("s%03d".format(it), Entry.Foreign("NL"), host = "10.1.0.$it") }
        var n = 0
        fun fail() = Letter.ProbeAnswered(100L + ++n, ServerId("s%03d".format(n)), Verdict.Unreachable)
        val letters = mutableListOf<Letter>(pressed(servers), Letter.LineObserved(10, open))
        repeat(2) { repeat(4) { letters += fail() }; letters += Letter.LineObserved(150L + n, open) }
        repeat(4) { letters += fail() }
        val (s, d) = run(*letters.toTypedArray())
        d shouldNotContain Deed.ProbeLine
        s.shouldBeInstanceOf<PressState.Running>()
    }

    test("все допустимые упали → Done, Remember без MakeExit") {
        val two = ru.take(2)
        val (s, d) = run(
            pressed(two), *confirmed,
            Letter.ProbeAnswered(100, ServerId("s001"), Verdict.Unreachable),
            Letter.ProbeAnswered(110, ServerId("s002"), Verdict.TelegramDc(1)),
            Letter.ProbeAnswered(900, ServerId("s002"), Verdict.TelegramDc(1)),
        )
        s.shouldBeInstanceOf<PressState.Done>()
        d.filterIsInstance<Deed.Remember>().size shouldBe 1
        d.filterIsInstance<Deed.MakeExit>().shouldBeEmpty()
    }

    test("ответ после Done → дел нет") {
        val (s, d) = run(
            pressed(ru), *confirmed,
            Letter.ProbeAnswered(100, ServerId("s001"), Verdict.TelegramDc(2)),
            Letter.ProbeAnswered(120, ServerId("s002"), Verdict.TelegramDc(2)),
        )
        d.shouldBeEmpty()
        s.shouldBeInstanceOf<PressState.Done>()
    }
})
