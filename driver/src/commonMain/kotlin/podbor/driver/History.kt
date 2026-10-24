package podbor.driver

import podbor.core.ExitState
import podbor.core.HARDCODED_SERVICES
import podbor.core.HistoryEvent
import podbor.core.Letter
import podbor.core.Mood
import podbor.core.OsNetwork
import podbor.core.ResultCard
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.exitHead
import podbor.core.exitNotes
import podbor.core.operatorOf
import podbor.core.refWords
import podbor.core.Deadline
import podbor.core.render

/**
 * История сервиса [service] — свёртка строк Ленты по его прогонам (нажатия и открытия): каждый законченный прогон —
 * одно событие словами экрана ([render]), с сетью, в которой он шёл. Свежие сверху, не больше [limit] — сколько
 * влезает в шторку. Незаконченный прогон (замер ещё идёт или оборван) — не событие.
 */
fun historyOf(rows: List<Row>, service: ServiceId, limit: Int): List<HistoryEvent> =
    when (val svc = HARDCODED_SERVICES.firstOrNull { it.id == service }) {
        null -> emptyList()
        else -> historyOf(rows, svc, limit)
    }

private fun historyOf(rows: List<Row>, svc: Service, limit: Int): List<HistoryEvent> {
    val service = svc.id
    val closed = rows.filterIsInstance<Row.Closed>().map { it.run }.toSet()
    val letters = rows.filterIsInstance<Row.Step>().groupBy({ it.run }, { it.letter })
    return rows.filterIsInstance<Row.Run>()
        .filter { serviceOf(it.head) == service }
        .mapNotNull { run ->
            when (val ls = letters[run.id]) {
                null -> null
                else -> eventOf(ls, svc, run.id in closed)
            }
        }
        .sortedByDescending { it.atMs }
        .take(limit)
}

private fun eventOf(letters: List<Letter>, service: Service, closed: Boolean): HistoryEvent? {
    val first = letters.first()
    val net = netOf(first)
    val screen = render(letters, service, if (closed) Deadline.Passed(letters.last().atMs) else Deadline.Open)
    return when (val r = screen.result) {
        ResultCard.Empty -> null
        is ResultCard.Found -> HistoryEvent(first.atMs, Mood.Good, "$net · подобрал ${refWords(r.serverName, r.transport)} — ${r.coverage}")
        is ResultCard.NotFound -> HistoryEvent(first.atMs, Mood.Bad, "$net · не нашёл: ${r.lines.firstOrNull().orEmpty()}")
        is ResultCard.Exit -> exitEventOf(r.state, first.atMs, net, service)
    }
}

/** Открытие проверило выход: работает или мёртв — событие; выхода нет или проверка не кончилась — нет. */
private fun exitEventOf(e: ExitState, atMs: Long, net: String, service: Service): HistoryEvent? = when (e) {
    is ExitState.Works -> HistoryEvent(atMs, Mood.Good, "$net · ${exitHead(e)} работает")
    is ExitState.Dead -> HistoryEvent(atMs, Mood.Bad, "$net · ${exitHead(e)}: ${exitNotes(e, service).first()}")
    ExitState.Off, is ExitState.Unlisted, is ExitState.Checking -> null
}

/** Сеть прогона словом: Wi-Fi или оператор; без сети — «нет сети». */
private fun netOf(first: Letter): String {
    val os = when (first) {
        is Letter.Pressed -> first.os
        is Letter.Opened -> first.os
        is Letter.Scan -> first.os
        is Letter.LineObserved, is Letter.ProbeAnswered, is Letter.ExitSeen -> OsNetwork.None
    }
    return when (val name = operatorOf(os)) {
        null -> "нет сети"
        else -> name
    }
}
