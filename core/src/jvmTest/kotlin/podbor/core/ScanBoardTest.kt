package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** Экран анализа как значение: производная букв, как render у нажатия. */
class ScanBoardTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val open = line(Reach.Up, Reach.Up)
    val whitelist = line(Reach.Up, Reach.Down)
    val yt = service(ServiceId("yt"), "YT", "Ютуб", probe = ProbeSpec.Telegram)
    val two = setOf(Telegram, Instagram)
    val three = setOf(Telegram, Instagram, yt)
    val tgOk = Verdict.TelegramDc(3)

    fun scan(servers: List<Server>, services: Set<Service> = two, hints: Map<String, WhiteHint> = emptyMap(), prior: Prior = Prior.None) =
        Letter.Scan(0, mts, servers, services.map { it.id }, hints, prior)

    val nl = { s: String -> server(s, Entry.Foreign("NL")) }

    test("до Scan и до ответа линии карточек нет") {
        scanBoard(two, emptyList()).cards shouldBe emptyList()
        val b = scanBoard(two, listOf(scan(listOf(nl("s001")))))
        b.total shouldBe 1
        b.cards shouldBe emptyList()
        b.finished shouldBe false
    }

    test("карточка до ответов: все метки Pending, live") {
        val b = scanBoard(three, listOf(scan(listOf(nl("s001")), three), Letter.LineObserved(10, open)))
        val c = b.cards.single()
        c.services shouldBe listOf("TG" to Mark3.Pending, "IG" to Mark3.Pending, "YT" to Mark3.Pending)
        c.live shouldBe true
        c.exit shouldBe Exit.Unknown
    }

    test("порядок: новейшая первой; карточка появляется со стартом задач сервера (бюджет на адрес)") {
        val servers = listOf(nl("s001"), server("s002", Entry.Foreign("NL"), host = "10.0.0.1"), nl("s003"))
        val b = scanBoard(two, listOf(scan(servers), Letter.LineObserved(10, open)))
        // s002 делит адрес с s001 — ждёт своей очереди.
        b.cards.map { it.key } shouldContainExactly listOf("key-s003", "key-s001")
        val later = scanBoard(two, listOf(scan(servers), Letter.LineObserved(10, open), Letter.ExitSeen(20, ServerId("s001"), Exit.At("1.1.1.1", "NL"))))
        later.cards.first().key shouldBe "key-s002"
    }

    /** Мир отвечает на дела по очереди (FIFO) — письма ровно те, что машина ждёт. */
    fun run(start: Letter.Scan, verdict: (ServerId, ServiceId) -> Verdict): List<Letter> {
        val letters = mutableListOf<Letter>(start)
        var (state, deeds) = scanStep(ScanState.Idle, start)
        val pending = ArrayDeque(deeds)
        var now = 0L
        while (pending.isNotEmpty()) {
            now += 10
            val letter = when (val d = pending.removeFirst()) {
                Deed.ProbeLine -> Letter.LineObserved(now, open)
                is Deed.MeasureExit -> Letter.ExitSeen(now, d.server, Exit.At("9.9.9.9", "NL"))
                is Deed.Probe -> Letter.ProbeAnswered(now, d.server, verdict(d.server, d.service), d.service)
                is Deed.MakeExit, is Deed.MeasureSpeed, is Deed.Remember, is Deed.SavePassport -> null
            } ?: continue
            letters += letter
            val (next, out) = scanStep(state, letter)
            state = next
            pending += out
        }
        return letters
    }

    test("полный прогон: счётчики, фишки, итог буква в букву") {
        val servers = listOf(server("s001", Entry.Ru), nl("s002"), nl("s003"))
        val hints = mapOf("key-s002" to WhiteHint.Likely, "key-s003" to WhiteHint.Likely)
        val letters = run(scan(servers, hints = hints)) { id, svc ->
            when {
                id.tag == "s003" -> Verdict.TelegramDc(0)
                svc == Telegram.id -> tgOk
                id.tag == "s001" -> Verdict.Instagram(cdnOk = true, wwwOk = true)
                else -> Verdict.Instagram(cdnOk = false, wwwOk = false)
            }
        }
        val b = scanBoard(two, letters)
        b.finished shouldBe true
        b.total shouldBe 3
        b.done shouldBe 3
        b.ruEntries shouldBe 1
        b.likelyWhite shouldBe 2
        b.tallies.map { it.label to it.works } shouldBe listOf("TG" to 2, "IG" to 1)
        b.verdict shouldBe listOf("Обычная сеть: TG ✓ 2 · IG ✓ 1", "Белые списки: вероятно 2 сервера")
        // Машина запускает s002, s003, s001 (порядок плана), карточки — от самой новой: s001 сверху.
        b.cards.map { it.key } shouldBe listOf("key-s001", "key-s003", "key-s002")
        val byKey = b.cards.associateBy { it.key }
        byKey.getValue("key-s001").services shouldBe listOf("TG" to Mark3.Works, "IG" to Mark3.Works)
        byKey.getValue("key-s002").services shouldBe listOf("TG" to Mark3.Works, "IG" to Mark3.Dead)
        byKey.getValue("key-s003").services shouldBe listOf("TG" to Mark3.Dead, "IG" to Mark3.Dead)
        byKey.getValue("key-s001").exit shouldBe Exit.At("9.9.9.9", "NL")
        b.cards.all { !it.live } shouldBe true
    }

    test("прерванный анализ: сводка идёт, итога нет, готовность растёт по серверам") {
        val servers = listOf(nl("s001"), nl("s002"))
        val letters = run(scan(servers)) { _, _ -> tgOk }
        val cut = scanBoard(two, letters.take(4)) // Scan, линия, два выхода
        cut.finished shouldBe false
        cut.verdict shouldBe emptyList()
        cut.done shouldBe 0
        scanBoard(two, letters).done shouldBe 2
    }

    test("нет вероятно-белых: итог про отсутствие подходящих серверов") {
        val s = nl("s001")
        val letters = listOf(
            scan(listOf(s)), Letter.LineObserved(10, open), Letter.ExitSeen(20, s.id, Exit.Unknown),
            Letter.ProbeAnswered(30, s.id, Verdict.Unreachable, Telegram.id),
        )
        val b = scanBoard(two, letters)
        b.finished shouldBe true
        b.verdict shouldBe listOf("Обычная сеть: TG ✓ 0 · IG ✓ 0", "Белые списки: в этой подписке нет подходящих серверов")
    }

    test("третий сервис с меткой YT — третья фишка, третья строка карточки, строка итога") {
        val s = nl("s001")
        val letters = listOf(
            scan(listOf(s), three), Letter.LineObserved(10, open), Letter.ExitSeen(20, s.id, Exit.At("1.1.1.1", "NL")),
            Letter.ProbeAnswered(30, s.id, tgOk, Telegram.id),
            Letter.ProbeAnswered(30, s.id, Verdict.Instagram(cdnOk = true, wwwOk = true), Instagram.id),
            Letter.ProbeAnswered(30, s.id, tgOk, yt.id),
        )
        val b = scanBoard(three, letters)
        b.tallies.map { it.label } shouldBe listOf("TG", "IG", "YT")
        b.cards.single().services.map { it.first } shouldBe listOf("TG", "IG", "YT")
        b.verdict.first() shouldBe "Обычная сеть: TG ✓ 1 · IG ✓ 1 · YT ✓ 1"
    }

    test("белые списки во время анализа: пояснение про вероятно-белые, обычной сети пока нет") {
        val rounds = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, whitelist), Letter.LineObserved(10, whitelist)))
        val servers = listOf(server("s001", Entry.Ru), nl("s002"))
        val b = scanBoard(two, listOf(scan(servers, prior = rounds).copy(atMs = 20)))
        b.finished shouldBe false
        b.verdict shouldBe listOf("сейчас белые списки — проверил только вероятно-белые; обычную сеть проверю, когда она вернётся")
        b.cards.map { it.key } shouldBe listOf("key-s001")
    }

    test("Pending → Dead и live → false посреди прогона (Unreachable на одном из серверов)") {
        val servers = listOf(nl("s001"), nl("s002"))
        val head = listOf(scan(servers), Letter.LineObserved(10, open), Letter.ExitSeen(20, ServerId("s001"), Exit.At("1.1.1.1", "NL")), Letter.ExitSeen(20, ServerId("s002"), Exit.At("198.51.100.2", "NL")))
        val before = scanBoard(two, head).cards.associateBy { it.key }
        before.getValue("key-s001").services shouldBe listOf("TG" to Mark3.Pending, "IG" to Mark3.Pending)
        before.getValue("key-s001").live shouldBe true
        val after = scanBoard(two, head + Letter.ProbeAnswered(30, ServerId("s001"), Verdict.Unreachable, Telegram.id)).cards.associateBy { it.key }
        after.getValue("key-s001").services shouldBe listOf("TG" to Mark3.Dead, "IG" to Mark3.Dead)
        after.getValue("key-s001").live shouldBe false
        after.getValue("key-s002").live shouldBe true
    }

    test("закончили — done = total, даже если сервера отсеяны сценарием") {
        val rounds = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, whitelist), Letter.LineObserved(10, whitelist)))
        val servers = listOf(server("s001", Entry.Ru), nl("s002"), nl("s003"))
        val start = scan(servers, hints = mapOf("key-s003" to WhiteHint.Likely), prior = rounds).copy(atMs = 20)
        val b = scanBoard(two, run(start) { _, _ -> tgOk })
        b.finished shouldBe true
        b.cards.size shouldBe 2
        b.done shouldBe 3
        b.total shouldBe 3
    }

    test("белые списки, анализ закончен: две строки итога, склонение по числу") {
        val rounds = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, whitelist), Letter.LineObserved(10, whitelist)))
        val servers = listOf(server("s001", Entry.Ru), nl("s002"))
        val start = scan(servers, hints = mapOf("key-s002" to WhiteHint.Likely), prior = rounds).copy(atMs = 20)
        scanBoard(two, run(start) { _, _ -> tgOk }).verdict shouldBe listOf(
            "сейчас белые списки — проверил только вероятно-белые; обычную сеть проверю, когда она вернётся",
            "Белые списки: вероятно 1 сервер",
        )
    }

    test("карточка: SNI белый — своя пометка, Likely от неё не зависит") {
        val s = nl("s001")
        val b = scanBoard(two, listOf(scan(listOf(s)).copy(sniWhite = setOf("key-s001")), Letter.LineObserved(10, open)))
        b.cards.single().sniWhite shouldBe true
        b.cards.single().whiteHint shouldBe WhiteHint.Unlikely
        b.likelyWhite shouldBe 0
    }

    test("офлайн: честный итог — нет сети, без «TG ✓ 0»") {
        val offline = line(Reach.Down, Reach.Down)
        val b = scanBoard(two, listOf(scan(listOf(nl("s001"))), Letter.LineObserved(10, offline)))
        b.finished shouldBe true
        b.verdict shouldBe listOf("нет сети — проверю серверы, когда она появится")
    }

    test("подозрение на белые списки (SuspectWhitelist) — итог как при белых списках") {
        // Раунд «похоже на белые», потом неясный: сценарий SuspectWhitelist (scenarioOf).
        val unclear = LineObservation(
            russia = nonEmptyListOf(0, 1, 2).map { Sample("ru$it", Handshake.Ok(30)) },
            abroad = nonEmptyListOf(0, 1, 2).map { Sample("far$it", Handshake.Unknown("dns")) },
        )
        val rounds = Prior.Detected(nonEmptyListOf(Letter.LineObserved(0, whitelist), Letter.LineObserved(10, unclear)))
        val servers = listOf(server("s001", Entry.Ru), nl("s002"))
        val start = scan(servers, hints = mapOf("key-s001" to WhiteHint.Likely), prior = rounds).copy(atMs = 20)
        val letters = run(start) { _, _ -> tgOk }
        scanBoard(two, letters).verdict shouldBe listOf(
            "сейчас белые списки — проверил только вероятно-белые; обычную сеть проверю, когда она вернётся",
            "Белые списки: вероятно 1 сервер",
        )
    }
})
