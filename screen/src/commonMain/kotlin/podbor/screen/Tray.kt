package podbor.screen

import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.NetworkMove
import podbor.core.moveWords

/**
 * Чем Podbor занят сверх сессии: ничем; перемером после переезда сети (сессия поднята); анализом подписки (VPN на его
 * время выключен), проверено [Analysis.done] из [Analysis.total].
 */
sealed interface Work {
    data object Idle : Work
    data class Remeasure(val move: NetworkMove) : Work
    data class Analysis(val done: Int, val total: Int) : Work
}

/**
 * Состояние Podbor одним значением — единственный источник для всего, что его показывает (экран, уведомление,
 * плитка): каждая — проекция [Status], и новое состояние (вариант [Work]) компилятор требует решить в каждой.
 * [exit] — имя общего выхода поднятой сессии.
 */
data class Status(val session: SessionView, val board: Board, val work: Work = Work.Idle, val exit: String = NO_EXIT)

/** Выхода нет или он не выбран. */
const val NO_EXIT = "выход не выбран"

/** Podbor в шторке — плитка и уведомление: включён ли и что сейчас видит. [on] — тап по плитке выключит. */
data class Tray(val on: Boolean, val line: String)

/** Шторка без сессии — и до первого запуска службы, и после её конца. */
val TrayOff: Tray = Tray(on = false, line = "выключен")

/**
 * Статус шторки: идёт анализ — он (VPN выключен); перемер на поднятой — его строка; иначе по сессии: «выключен»,
 * «подбираю…», по сервису «IG ✓ · TG ✗».
 */
fun trayOf(s: Status): Tray = when (val w = s.work) {
    is Work.Analysis -> Tray(on = false, line = "анализирую подписку · ${w.done} из ${w.total}")
    is Work.Remeasure -> sessionTray(s.session, s.board).let { t -> if (t.on) t.copy(line = "${moveWords(w.move)} — проверяю выходы") else t }
    Work.Idle -> sessionTray(s.session, s.board)
}

private fun sessionTray(session: SessionView, board: Board): Tray = when (session) {
    SessionView.Down -> TrayOff
    SessionView.Starting -> Tray(on = true, line = "включаю…")
    is SessionView.Failed -> Tray(on = false, line = "не включился — открой Podbor")
    SessionView.Up -> Tray(on = true, line = upLine(board))
}

/** Поднята: идёт подбор — «подбираю…», иначе по сервису «IG ✓ · TG ✗». */
private fun upLine(board: Board): String = when (board.button) {
    Button.Searching -> "подбираю…"
    Button.Idle, Button.Ready, Button.Green, Button.Red -> board.cards.joinToString(" · ") { "${it.label} ${markOf(it.status)}" }
}

/** Кнопка уведомления. */
enum class ShadeButton { Press, Stop }

/**
 * Уведомление сессии — что должно быть в шторке сейчас, целиком: снято ([Hidden] — убрать, если висит прежнее) или
 * строка под выходом и кнопки. «Ничего не делать» вариантом нет: отрисовщик каждый раз приводит шторку к значению.
 */
sealed interface Shade {
    data object Hidden : Shade
    data class Shown(val line: String, val buttons: List<ShadeButton>) : Shade
}

/**
 * Уведомление — из того же [Status], что плитка: строка — её. Идёт анализ — снято (VPN выключен). Нет сессии — снято;
 * поднимается — только «Выключить»; поднята — «Подобрать» и «Выключить».
 */
fun shadeOf(s: Status): Shade = when (s.work) {
    is Work.Analysis -> Shade.Hidden
    is Work.Remeasure, Work.Idle -> sessionShade(s.session, trayOf(s).line)
}

private fun sessionShade(session: SessionView, line: String): Shade = when (session) {
    SessionView.Down, is SessionView.Failed -> Shade.Hidden
    SessionView.Starting -> Shade.Shown(line, listOf(ShadeButton.Stop))
    SessionView.Up -> Shade.Shown(line, listOf(ShadeButton.Press, ShadeButton.Stop))
}

/** Что делает тап по плитке. */
enum class Tap { Stop, Start, OpenApp }

/**
 * Включён — выключить; выключен — включить. Согласия на VPN ещё нет — открыть Podbor: системный вопрос задаёт экран,
 * из шторки его не задать.
 */
fun tapOf(tray: Tray, consented: Boolean): Tap = when {
    tray.on -> Tap.Stop
    consented -> Tap.Start
    else -> Tap.OpenApp
}

private fun markOf(status: CardStatus): String = when (status) {
    is CardStatus.Works, is CardStatus.Found -> "✓"
    is CardStatus.Dead, is CardStatus.NotFound -> "✗"
    is CardStatus.Checking, CardStatus.Searching -> "…"
    CardStatus.Unknown, is CardStatus.Unlisted -> "—"
}
