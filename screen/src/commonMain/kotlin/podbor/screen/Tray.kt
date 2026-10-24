package podbor.screen

import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus

/** Podbor в шторке — плитка и уведомление: включён ли и что сейчас видит. [on] — тап по плитке выключит. */
data class Tray(val on: Boolean, val line: String)

/** Шторка без сессии — и до первого запуска службы, и после её конца. */
val TrayOff: Tray = Tray(on = false, line = "выключен")

/** Статус шторки из сессии и сводного экрана: «выключен», «подбираю…», по сервису «IG ✓ · TG ✗». */
fun trayOf(session: SessionView, board: Board): Tray = when (session) {
    SessionView.Down -> TrayOff
    SessionView.Starting -> Tray(on = true, line = "включаю…")
    is SessionView.Failed -> Tray(on = false, line = "не включился — открой Podbor")
    SessionView.Up -> Tray(
        on = true,
        line = when (board.button) {
            Button.Searching -> "подбираю…"
            Button.Idle, Button.Ready, Button.Green, Button.Red -> board.cards.joinToString(" · ") { "${it.label} ${markOf(it.status)}" }
        },
    )
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
