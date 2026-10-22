package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Машина анализа подписки: буквы мира в паспорт по набору сервисов. */
class ScanTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val open = line(Reach.Up, Reach.Up)
    val whitelist = line(Reach.Up, Reach.Down)
    val tg = Telegram.id
    val insta = Instagram.id
    val yt = ServiceId("yt") // выдуманный сервис: анализ не знает сервисов по именам

    fun scan(
        servers: List<Server>,
        services: List<ServiceId> = listOf(tg, insta),
        hints: Map<String, WhiteHint> = emptyMap(),
        prior: Prior = Prior.None,
        memory: Memory = Memory.Empty,
        at: Long = 0,
        confirmFits: Boolean = false,
    ) = Letter.Scan(atMs = at, os = mts, servers = servers, services = services, hints = hints, prior = prior, memory = memory, confirmFits = confirmFits)

    /** Ответы мира на дела; null — мир молчит (дело остаётся висеть). */
    class World(
        val line: LineObservation = open,
        val exit: (ServerId) -> Exit = { Exit.At("203.0.113.${it.tag.filter(Char::isDigit).toInt()}", "NL") },
        val verdict: (ServerId, ServiceId) -> Verdict = { _, _ -> Verdict.TelegramDc(3) },
    )

    data class Trace(val state: ScanState, val deeds: List<Deed>, val maxPerEndpoint: Int)

    /**
     * Прогнать анализ до конца: дела исполняются по одному в порядке выдачи (FIFO), время идёт по 100 мс.
     * Заодно меряем, сколько задач на один адрес висело разом.
     */
    fun drive(start: Letter.Scan, world: World = World()): Trace {
        val byId = start.servers.associateBy { it.id }
        var (state, deeds) = scanStep(ScanState.Idle, start)
        val all = deeds.toMutableList()
        val pending = ArrayDeque(deeds)
        val busy = mutableListOf<Endpoint>()
        var maxPer = 0
        var now = start.atMs
        fun track(ds: List<Deed>) = ds.forEach { d ->
            when (d) {
                is Deed.MeasureExit -> busy += byId.getValue(d.server).endpoint
                is Deed.Probe -> busy += byId.getValue(d.server).endpoint
                else -> Unit
            }
            maxPer = maxOf(maxPer, busy.groupingBy { it }.eachCount().values.maxOrNull() ?: 0)
        }
        track(deeds)
        while (pending.isNotEmpty()) {
            now += 100
            val letter = when (val d = pending.removeFirst()) {
                Deed.ProbeLine -> Letter.LineObserved(now, world.line)
                is Deed.MeasureExit -> Letter.ExitSeen(now, d.server, world.exit(d.server)).also { busy -= byId.getValue(d.server).endpoint }
                is Deed.Probe ->
                    Letter.ProbeAnswered(now, d.server, world.verdict(d.server, d.service), d.service).also { busy -= byId.getValue(d.server).endpoint }
                is Deed.MakeExit, is Deed.MeasureSpeed, is Deed.Remember, is Deed.SavePassport -> null
            } ?: continue
            val (next, out) = scanStep(state, letter)
            state = next
            all += out
            pending += out
            track(out)
        }
        return Trace(state, all, maxPer)
    }

    fun Trace.probes() = deeds.filterIsInstance<Deed.Probe>().map { it.server.tag to it.service }
    fun Trace.exits() = deeds.filterIsInstance<Deed.MeasureExit>().map { it.server.tag }
    fun Trace.passport() = state.shouldBeInstanceOf<ScanState.Done>().passport

    test("старт без свежей линии → ProbeLine") {
        scanStep(ScanState.Idle, scan(listOf(server("s001", Entry.Ru)))).second shouldContainExactly listOf(Deed.ProbeLine)
    }

    test("открытая сеть, 2 сервера × 2 сервиса: выход и пробы всем, паспорт с Exit.At и Seen.Answered") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Ru, transport = Transport.RealityVision))
        val t = drive(scan(servers))
        t.exits() shouldContainExactlyInAnyOrder listOf("s001", "s002")
        t.probes() shouldContainExactlyInAnyOrder listOf("s001" to tg, "s001" to insta, "s002" to tg, "s002" to insta)
        val p = t.passport()
        p.servers.keys shouldBe setOf("key-s001", "key-s002")
        p.servers.getValue("key-s001").exit shouldBe Exit.At("203.0.113.1", "NL")
        p.servers.getValue("key-s001").seen.shouldBeInstanceOf<Seen.Answered>()
        p.servers.getValue("key-s002").entry shouldBe Entry.Ru
        p.servers.getValue("key-s002").transport shouldBe Transport.RealityVision
        p.servers.getValue("key-s002").whiteHint shouldBe WhiteHint.Unlikely
    }

    test("выход меряется в первую очередь: первые дела после линии — MeasureExit") {
        val servers = (1..3).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val (s, _) = scanStep(ScanState.Idle, scan(servers))
        scanStep(s, Letter.LineObserved(10, open)).second shouldContainExactly servers.map { Deed.MeasureExit(it.id) }
    }

    test("белые списки: Foreign+Unlikely не трогаем, Likely Foreign — трогаем; в паспорт — только проверенные") {
        val rounds = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, whitelist), Letter.LineObserved(10, whitelist)))
        val servers = listOf(server("s001", Entry.Ru), server("s002", Entry.Foreign("NL")), server("s003", Entry.Foreign("DE")))
        val t = drive(scan(servers, hints = mapOf("key-s003" to WhiteHint.Likely), prior = rounds, at = 20), World(line = whitelist))
        t.exits() shouldContainExactlyInAnyOrder listOf("s001", "s003")
        t.probes().map { it.first }.toSet() shouldBe setOf("s001", "s003")
        val p = t.passport()
        p.servers shouldNotContainKey "key-s002"
        p.servers.getValue("key-s003").whiteHint shouldBe WhiteHint.Likely
        t.state.shouldBeInstanceOf<ScanState.Done>().diagnosis.scenario shouldBe Scenario.MobileWhitelist
    }

    test("выдуманный третий сервис получает пробы и свой ключ памяти") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        val t = drive(scan(servers, services = listOf(tg, insta, yt)))
        t.probes().filter { it.second == yt }.map { it.first } shouldContainExactlyInAnyOrder listOf("s001", "s002")
        val memory = t.deeds.filterIsInstance<Deed.Remember>().last().memory
        memory.successes.getValue(memoryKey(mts, Scenario.MobileOpen, yt)).map { it.serverKey } shouldContainExactlyInAnyOrder
            listOf("key-s001", "key-s002")
    }

    test("успех сервиса → Remember под ключ (сеть, сценарий, этот сервис); провал — не в память") {
        val servers = listOf(server("s001", Entry.Foreign("NL")))
        val t = drive(scan(servers), World(verdict = { _, svc -> if (svc == tg) Verdict.TelegramDc(3) else Verdict.Instagram(cdnOk = true, wwwOk = false) }))
        val memory = t.deeds.filterIsInstance<Deed.Remember>().last().memory
        memory.successes.getValue(memoryKey(mts, Scenario.MobileOpen, tg)).map { it.serverKey } shouldContainExactly listOf("key-s001")
        memory.successes shouldNotContainKey memoryKey(mts, Scenario.MobileOpen, insta)
    }

    test("провал сервиса не делает Silent, если выход ответил; Silent — когда не ответило ничего") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        val t = drive(
            scan(servers),
            World(
                exit = { if (it.tag == "s001") Exit.At("203.0.113.1", "NL") else Exit.Unknown },
                verdict = { _, _ -> Verdict.Unreachable },
            ),
        )
        val p = t.passport()
        p.servers.getValue("key-s001").seen.shouldBeInstanceOf<Seen.Answered>()
        p.servers.getValue("key-s002").seen shouldBe Seen.Silent
        p.servers.getValue("key-s002").exit shouldBe Exit.Unknown
    }

    test("ответ сервиса через туннель (даже мёртвый сервис) — Answered, хоть выход и не узнан") {
        val t = drive(scan(listOf(server("s001", Entry.Foreign("NL")))), World(exit = { Exit.Unknown }, verdict = { _, _ -> Verdict.TelegramDc(0) }))
        t.passport().servers.getValue("key-s001").seen.shouldBeInstanceOf<Seen.Answered>()
    }

    test("Done даёт SavePassport ровно один раз; поздние буквы — ничего") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Ru))
        val t = drive(scan(servers))
        val saves = t.deeds.filterIsInstance<Deed.SavePassport>()
        saves shouldHaveSize 1
        saves.single().passport shouldBe t.passport()
        scanStep(t.state, Letter.ProbeAnswered(99_999, ServerId("s001"), Verdict.TelegramDc(3), tg)).second.shouldBeEmpty()
        scanStep(t.state, Letter.LineObserved(99_999, open)).second.shouldBeEmpty()
    }

    test("TlsSilence дважды → блок IP:порт в памяти, прочие задачи этого адреса снимаются; сервер Silent") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        val t = drive(
            scan(servers),
            World(exit = { if (it.tag == "s001") Exit.Unknown else Exit.At("203.0.113.2", "NL") }, verdict = { id, _ -> if (id.tag == "s001") Verdict.TlsSilence else Verdict.TelegramDc(3) }),
        )
        // Тишина — подпись адреса, не сервиса: вторая на том же IP:порт (здесь — у insta) ставит блок, повтор tg снят.
        t.probes().filter { it.first == "s001" } shouldContainExactly listOf("s001" to tg, "s001" to insta)
        val memory = t.deeds.filterIsInstance<Deed.Remember>().last().memory
        memory.isBlocked(Endpoint("10.0.0.1", 443), t.passport().analyzedAtMs) shouldBe true
        t.passport().servers.getValue("key-s001").seen shouldBe Seen.Silent
    }

    test("тишина → ответ → тишина на одном адресе: не подряд — блока нет, сервер Answered") {
        val t = drive(
            scan(listOf(server("s001", Entry.Foreign("NL")))),
            World(exit = { Exit.Unknown }, verdict = { _, svc -> if (svc == tg) Verdict.TlsSilence else Verdict.Instagram(cdnOk = true, wwwOk = true) }),
        )
        // tg: тишина (повтор в конец), insta: ответ — сбрасывает тишину адреса, tg повтор: снова тишина — первая подряд.
        t.probes() shouldContainExactly listOf("s001" to tg, "s001" to insta, "s001" to tg)
        val memory = t.deeds.filterIsInstance<Deed.Remember>().last().memory
        memory.blocks shouldBe emptyMap()
        t.passport().servers.getValue("key-s001").seen.shouldBeInstanceOf<Seen.Answered>()
    }

    test("частичный паспорт: ответившие и закончившие — да, ждущие в очереди или в полёте — нет") {
        val servers = listOf(
            server("s001", Entry.Foreign("NL")),
            server("s002", Entry.Foreign("NL")),
            server("s003", Entry.Foreign("NL"), host = "10.0.0.1"), // делит адрес с s001 — ждёт в очереди
        )
        ScanState.Idle.partialPassport(0) shouldBe null
        var s: ScanState = scanStep(ScanState.Idle, scan(servers, services = listOf(tg))).first
        listOf(
            Letter.LineObserved(10, open),
            Letter.ExitSeen(20, ServerId("s001"), Exit.At("203.0.113.1", "NL")), // ответил; его проба теперь в полёте
            Letter.ExitSeen(30, ServerId("s002"), Exit.Unknown),
            Letter.ProbeAnswered(40, ServerId("s002"), Verdict.Unreachable, tg), // задачи кончились, ничего не ответило
        ).forEach { s = scanStep(s, it).first }
        s.shouldBeInstanceOf<ScanState.Running>()
        val p = s.partialPassport(50).shouldBeInstanceOf<Passport>()
        p.servers.keys shouldBe setOf("key-s001", "key-s002")
        p.servers.getValue("key-s001").exit shouldBe Exit.At("203.0.113.1", "NL")
        p.servers.getValue("key-s002").seen shouldBe Seen.Silent
        p.analyzedAtMs shouldBe 50
        val done = drive(scan(servers, services = listOf(tg)))
        done.state.partialPassport(99_999) shouldBe done.passport()
    }

    test("заблокированный адрес не трогаем и в паспорт не пишем — проверим, когда блок сойдёт") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        val memory = Memory.Empty.block(Endpoint("10.0.0.1", 443), 0)
        val t = drive(scan(servers, memory = memory))
        t.exits() shouldNotContain "s001"
        t.passport().servers shouldNotContainKey "key-s001"
    }

    test("SNI из белого списка — отдельный факт паспорта: sniWhite, на whiteHint не влияет") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        val t = drive(scan(servers).copy(sniWhite = setOf("key-s002")))
        val p = t.passport()
        p.servers.getValue("key-s002").sniWhite shouldBe true
        p.servers.getValue("key-s002").whiteHint shouldBe WhiteHint.Unlikely
        p.servers.getValue("key-s001").sniWhite shouldBe false
    }

    test("свой бюджет анализа: 12 задач разом, на адрес — по-прежнему одна, и когда бюджет полон (общие адреса)") {
        // 20 серверов на 15 адресах: s001–s005 делят адрес с s016–s020 — общие адреса ждут, пока бюджет занят.
        val servers = (1..20).map { server("s%03d".format(it), Entry.Foreign("NL"), host = "10.0.0.${(it - 1) % 15}") }
        val (s, _) = scanStep(ScanState.Idle, scan(servers))
        val first = scanStep(s, Letter.LineObserved(10, open)).second
        first shouldHaveSize 12
        val t = drive(scan(servers))
        t.maxPerEndpoint shouldBe 1
        t.exits() shouldHaveSize 20
    }

    test("свежая линия детектора — без своего замера; подозрение — подтверждающий ProbeLine") {
        val confirmed = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, open)))
        scanStep(ScanState.Idle, scan(listOf(server("s001", Entry.Ru)), prior = confirmed, at = 10)).second shouldContainExactly
            listOf(Deed.MeasureExit(ServerId("s001")))
        val suspected = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, whitelist)))
        scanStep(ScanState.Idle, scan(listOf(server("s001", Entry.Ru)), prior = suspected, at = 10)).second shouldContainExactly
            listOf(Deed.ProbeLine)
    }

    test("офлайн → Done с пустым паспортом, без проб") {
        val t = drive(scan(listOf(server("s001", Entry.Ru))), World(line = line(Reach.Down, Reach.Down)))
        t.passport().servers.keys.shouldBeEmpty()
        t.exits().shouldBeEmpty()
        t.deeds.filterIsInstance<Deed.SavePassport>() shouldHaveSize 1
    }

    test("Ленты v1/v2: Probe и ProbeAnswered без service читаются как Телега") {
        val json = kotlinx.serialization.json.Json
        json.decodeFromString(Deed.serializer(), """{"type":"podbor.core.Deed.ProbeTelegram","server":"s001"}""") shouldBe
            Deed.Probe(ServerId("s001"))
        val a = json.decodeFromString(Letter.serializer(), """{"type":"podbor.core.Letter.ProbeAnswered","atMs":1,"server":"s001","verdict":{"type":"podbor.core.Verdict.Unreachable"}}""")
        a.shouldBeInstanceOf<Letter.ProbeAnswered>().service shouldBe tg
        listOf(a) shouldContain Letter.ProbeAnswered(1, ServerId("s001"), Verdict.Unreachable)
        // Как пишет Лента: умолчание не на проводе, заполненный анализом сервис — на проводе и читается обратно.
        val tape = kotlinx.serialization.json.Json { encodeDefaults = true }
        tape.encodeToString(Deed.serializer(), Deed.Probe(ServerId("s001"))) shouldBe """{"type":"podbor.core.Deed.ProbeTelegram","server":"s001"}"""
        val filled = Deed.Probe(ServerId("s001"), yt)
        tape.decodeFromString(Deed.serializer(), tape.encodeToString(Deed.serializer(), filled)) shouldBe filled
    }

    // Один выстрел завышает успех: два прогона одной подписки подряд давали заметно разный счёт Телеги.
    test("подтверждение: успех засчитан и записан в память только вторым успехом той же пробы") {
        val servers = listOf(server("s001", Entry.Foreign("NL")))
        val t = drive(scan(servers, services = listOf(tg), confirmFits = true))
        t.probes() shouldContainExactly listOf("s001" to tg, "s001" to tg)
        val remembers = t.deeds.filterIsInstance<Deed.Remember>()
        remembers.size shouldBe 1
        remembers.single().memory.successes.getValue(memoryKey(mts, Scenario.MobileOpen, tg)).map { it.serverKey } shouldContainExactly listOf("key-s001")
    }

    test("подтверждение: второй ответ не успех — шум момента: ни памяти, ни повторов сверх второго") {
        val servers = listOf(server("s001", Entry.Foreign("NL")))
        var n = 0
        val t = drive(
            scan(servers, services = listOf(tg), confirmFits = true),
            World(verdict = { _, _ -> if (n++ == 0) Verdict.TelegramDc(3) else Verdict.TelegramDc(0) }),
        )
        t.probes() shouldContainExactly listOf("s001" to tg, "s001" to tg)
        t.deeds.filterIsInstance<Deed.Remember>().flatMap { it.memory.successes.keys } shouldNotContain memoryKey(mts, Scenario.MobileOpen, tg)
        t.passport().servers.getValue("key-s001").seen.shouldBeInstanceOf<Seen.Answered>()
    }

    test("подтверждение на доске: пока ждём второй ответ — «…», засчитан — ✓; не подтвердился — ✗") {
        val servers = listOf(server("s001", Entry.Foreign("NL")), server("s002", Entry.Foreign("NL")))
        val start = scan(servers, services = listOf(tg), confirmFits = true)
        var s002 = 0
        val letters = mutableListOf<Letter>(start)
        var state = scanStep(ScanState.Idle, start).first
        fun feed(l: Letter) { letters += l; state = scanStep(state, l).first }
        feed(Letter.LineObserved(10, open))
        feed(Letter.ExitSeen(20, ServerId("s001"), Exit.Unknown))
        feed(Letter.ExitSeen(30, ServerId("s002"), Exit.Unknown))
        feed(Letter.ProbeAnswered(40, ServerId("s001"), Verdict.TelegramDc(3), tg))
        feed(Letter.ProbeAnswered(50, ServerId("s002"), Verdict.TelegramDc(3), tg))
        fun marks() = scanBoard(setOf(Telegram), letters).cards.associate { it.key to it.services.single().second }
        marks() shouldBe mapOf("key-s001" to Mark3.Pending, "key-s002" to Mark3.Pending)
        feed(Letter.ProbeAnswered(60, ServerId("s001"), Verdict.TelegramDc(3), tg))
        feed(Letter.ProbeAnswered(70, ServerId("s002"), Verdict.TelegramDc(0), tg))
        marks() shouldBe mapOf("key-s001" to Mark3.Works, "key-s002" to Mark3.Dead)
        scanBoard(setOf(Telegram), letters).tallies.single().works shouldBe 1
    }
})
