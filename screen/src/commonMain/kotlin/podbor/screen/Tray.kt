package podbor.screen

import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.NetworkMove
import podbor.core.moveWords

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

/** Идёт ли перемер после переезда сети — его строка сильнее статуса шторки. */
sealed interface Remeasure {
    data object Idle : Remeasure
    data class After(val move: NetworkMove) : Remeasure
}

/** Кнопка уведомления. */
enum class ShadeButton { Press, Stop }

/** Уведомление сессии: нет ([None]) или строка под выходом и кнопки. */
sealed interface Shade {
    data object None : Shade
    data class Shown(val line: String, val buttons: List<ShadeButton>) : Shade
}

/**
 * Уведомление — из одного значения сессии: и есть ли оно, и строка, и кнопки. Нет сессии — нет уведомления;
 * поднимается — только «Выключить»; поднята — «Подобрать» и «Выключить».
 */
fun shadeOf(session: SessionView, board: Board, remeasure: Remeasure): Shade {
    val line = when (remeasure) {
        Remeasure.Idle -> trayOf(session, board).line
        is Remeasure.After -> "${moveWords(remeasure.move)} — проверяю выходы"
    }
    return when (session) {
        SessionView.Down, is SessionView.Failed -> Shade.None
        SessionView.Starting -> Shade.Shown(line, listOf(ShadeButton.Stop))
        SessionView.Up -> Shade.Shown(line, listOf(ShadeButton.Press, ShadeButton.Stop))
    }
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
