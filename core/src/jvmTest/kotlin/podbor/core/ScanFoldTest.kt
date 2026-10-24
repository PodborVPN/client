package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Доска по шагам ([ScanFold]) — та же, что свёртка всех писем заново (прежний scanBoard, оракул ниже) после каждой
 * буквы: на 88 серверах × 3 сервиса, с повторами, тишиной и недоступными.
 */
class ScanFoldTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val open = line(Reach.Up, Reach.Up)
    val yt = service(ServiceId("yt"), "YT", "Ютуб", probe = ProbeSpec.Telegram)
    val three = setOf(Telegram, Instagram, yt)

    /** 88 серверов, часть делит адрес; ответы разные: работает, сервис молчит, тишина, недоступен. */
    fun letters(): List<Letter> {
        val servers = (1..88).map { i ->
            server("s%03d".format(i), if (i % 4 == 0) Entry.Ru else Entry.Foreign("NL"), host = "10.0.${i % 70}.1")
        }
        val start = Letter.Scan(
            0, mts, servers, three.map { it.id }, servers.filterIndexed { i, _ -> i % 3 == 0 }.associate { it.key to WhiteHint.Likely },
            sniWhite = servers.take(5).map { it.key }.toSet(),
        )
        val out = mutableListOf<Letter>(start)
        var (state, deeds) = scanStep(ScanState.Idle, start)
        val pending = ArrayDeque(deeds)
        var now = 0L
        while (pending.isNotEmpty()) {
            now += 10
            val letter = when (val d = pending.removeFirst()) {
                Deed.ProbeLine -> Letter.LineObserved(now, open)
                is Deed.MeasureExit -> Letter.ExitSeen(now, d.server, if (d.server.tag.last() == '7') Exit.Unknown else Exit.At("9.9.9.9", "NL"))
                is Deed.Probe -> Letter.ProbeAnswered(now, d.server, when (d.server.tag.last()) {
                    '1', '2', '3' -> Verdict.TelegramDc(3)
                    '4' -> Verdict.TelegramDc(0)
                    '5' -> Verdict.Unreachable
                    else -> Verdict.Instagram(cdnOk = true, wwwOk = true)
                }, d.service)
                is Deed.MakeExit, is Deed.MeasureSpeed, is Deed.Remember, is Deed.SavePassport -> null
            } ?: continue
            out += letter
            val (next, more) = scanStep(state, letter)
            state = next
            pending += more
        }
        return out
    }

    test("по шагам — буква в букву как свёртка заново, на каждой букве; конец — finished") {
        val all = letters()
        var state: ScanState = ScanState.Idle
        var fold = ScanFold(three)
        val folded = all.map { l ->
            state = scanStep(state, l).first
            fold = fold.next(l, state)
            fold.board()
        }
        folded.last().finished shouldBe true
        folded.indices.forEach { i -> folded[i] shouldBe referenceBoard(three, all.take(i + 1)) }
        scanBoard(three, all) shouldBe folded.last()
    }
})

/** Прежняя доска: свёртка всех писем заново — оракул для [ScanFold]. */
private fun referenceBoard(services: Set<Service>, letters: List<Letter>): ScanBoard {
    var state: ScanState = ScanState.Idle
    val started = mutableListOf<ServerId>()
    val works = mutableSetOf<Pair<ServerId, ServiceId>>()
    var scan: Letter.Scan? = null
    for (letter in letters) {
        if (letter is Letter.Scan) scan = letter
        val (next, deeds) = scanStep(state, letter)
        if (letter is Letter.ProbeAnswered && state is ScanState.Running && letter.verdict.kind() == VerdictKind.Fits) {
            works += letter.server to letter.service
        }
        deeds.forEach { d ->
            val id = when (d) {
                is Deed.MeasureExit -> d.server
                is Deed.Probe -> d.server
                else -> null
            }
            if (id != null && id !in started) started += id
        }
        state = next
    }
    val begun = scan ?: return ScanBoard(0, 0, 0, 0, emptyList(), emptyList(), emptyList(), false)
    val shown = begun.services.mapNotNull { id -> services.find { it.id == id } }
    val tasks = when (val s = state) {
        is ScanState.Running -> s.queue + s.inFlight
        ScanState.Idle, is ScanState.Done -> emptyList()
    }
    val inFlight = (state as? ScanState.Running)?.inFlight.orEmpty()
    val exits = (state as? ScanState.Running)?.exits.orEmpty()
    val passport = (state as? ScanState.Done)?.passport
    val byId = begun.servers.associateBy { it.id }
    val cards = started.reversed().mapNotNull(byId::get).map { srv ->
        ServerCard(
            key = srv.key,
            entry = srv.entry,
            exit = exits[srv.id] ?: passport?.servers?.get(srv.key)?.exit ?: Exit.Unknown,
            whiteHint = begun.hints[srv.key] ?: WhiteHint.Unlikely,
            sniWhite = srv.key in begun.sniWhite,
            transport = srv.transport,
            services = shown.map { svc ->
                svc.label to when {
                    srv.id to svc.id in works -> Mark3.Works
                    tasks.any { it is ScanTask.Probe && it.server.id == srv.id && it.service == svc.id } -> Mark3.Pending
                    else -> Mark3.Dead
                }
            },
            live = inFlight.any { it.server.id == srv.id },
        )
    }
    val pendingIds = tasks.map { it.server.id }.toSet()
    val tallies = shown.map { svc -> ServiceTally(svc.id, svc.label, works.count { it.second == svc.id }) }
    val likelyWhite = begun.servers.count { begun.hints[it.key] == WhiteHint.Likely }
    val diagnosis = when (val s = state) {
        is ScanState.Running -> s.diagnosis
        is ScanState.Done -> s.diagnosis
        ScanState.Idle -> Diagnosis.Pending
    }
    val finished = state is ScanState.Done
    val whiteLine = if (likelyWhite > 0) "Белые списки: вероятно ${serversCount(likelyWhite)}" else "Белые списки: в этой подписке нет подходящих серверов"
    val verdict = when (diagnosis.scene) {
        Scene.Chosen(Scenario.MobileWhitelist), Scene.Chosen(Scenario.SuspectWhitelist) ->
            listOf("сейчас белые списки — проверил только вероятно-белые; обычную сеть проверю, когда она вернётся") +
                if (finished) listOf(whiteLine) else emptyList()
        Scene.Chosen(Scenario.Offline) -> listOf("нет сети — проверю серверы, когда она появится")
        else ->
            if (finished) listOf("Обычная сеть: " + tallies.joinToString(" · ") { "${it.label} ✓ ${it.works}" }, whiteLine) else emptyList()
    }
    return ScanBoard(
        total = begun.servers.size,
        // Закончили — все разобраны: отсеянные сценарием и блоком тоже «сделаны», табло сходится total/total.
        done = if (finished) begun.servers.size else started.count { it !in pendingIds },
        ruEntries = begun.servers.count { it.entry == Entry.Ru },
        likelyWhite = likelyWhite,
        tallies = tallies,
        cards = cards,
        verdict = verdict,
        finished = finished,
    )
}
