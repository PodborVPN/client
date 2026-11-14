package podbor.driver

import podbor.core.ExitState
import podbor.core.VPN_SERVICES
import podbor.core.HistoryEvent
import podbor.core.Letter
import podbor.core.Mood
import podbor.core.NetworkCard
import podbor.core.Scenario
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
    when (val svc = VPN_SERVICES.firstOrNull { it.id == service }) {
        null -> emptyList()
        else -> historyOf(rows, svc, limit)
    }

private fun historyOf(rows: List<Row>, svc: Service, limit: Int): List<HistoryEvent> {
    val service = svc.id
    val closed = rows.filterIsInstance<Row.Closed>().map { it.run }.toSet()
    val letters = rows.filterIsInstance<Row.Step>().groupBy({ it.run }, { it.letter })
    return rows.filterIsInstance<Row.Run>()
        .filter { serviceOf(it.head) == service || isRecovery(it.head) }
        .mapNotNull { run ->
            when (val ls = letters[run.id]) {
                null -> null
                else -> eventOf(ls, svc, run.id in closed, isRecovery(run.head))
            }
        }
        .plus(rows.filterIsInstance<Row.Recovery>().mapNotNull { recoveryEvent(it, service) })
        .sortedByDescending { it.atMs }
        .take(limit)
}

private fun recoveryEvent(row: Row.Recovery, service: ServiceId): HistoryEvent? {
    var state: podbor.core.RecoveryState = podbor.core.RecoveryState.Watching
    var affected = false
    val route = if (service == podbor.core.Web.id) podbor.core.RecoveryRoute.General
    else podbor.core.RecoveryRoute.Service(service)
    for (step in row.steps) {
        state = podbor.core.recoveryStep(state, step.letter).first
        if (state is podbor.core.RecoveryState.Searching && route in state.routes) affected = true
    }
    if (!affected) return null
    return when (state) {
        is podbor.core.RecoveryState.Settled -> recoveryResult(row.atMs, state.results[route])
        podbor.core.RecoveryState.Watching -> row.confirmed[route]?.let { recoveryResult(row.atMs, it) }
            ?: HistoryEvent(row.atMs, Mood.Info, "автоподбор · попытка отменена")
        is podbor.core.RecoveryState.Checking, is podbor.core.RecoveryState.Searching,
        is podbor.core.RecoveryState.Applying -> null
    }
}

private fun recoveryResult(atMs: Long, result: podbor.core.RecoveryApplied?): HistoryEvent = when (result) {
    podbor.core.RecoveryApplied.Applied -> HistoryEvent(atMs, Mood.Good, "автоподбор · переключил на проверенный выход")
    podbor.core.RecoveryApplied.Rejected -> HistoryEvent(atMs, Mood.Bad, "автоподбор · Xray оставил прежний выход")
    podbor.core.RecoveryApplied.Unknown -> HistoryEvent(atMs, Mood.Info, "автоподбор · проверяю, куда ведёт маршрут")
    null -> HistoryEvent(atMs, Mood.Bad, "автоподбор · рабочая замена не найдена")
}

private fun isRecovery(head: Head): Boolean = head is Head.Open && head.recovery

private fun eventOf(letters: List<Letter>, service: Service, closed: Boolean, recovery: Boolean): HistoryEvent? {
    val first = letters.first()
    val net = netOf(first)
    val screen = render(letters, service, if (closed) Deadline.Passed(letters.last().atMs) else Deadline.Open)
    if (recovery) {
        val network = screen.network
        return if (network is NetworkCard.Measured && network.scenario == Scenario.Offline) {
            HistoryEvent(first.atMs, Mood.Info, "$net · нет интернета, автоподбор отложен")
        } else null
    }
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
