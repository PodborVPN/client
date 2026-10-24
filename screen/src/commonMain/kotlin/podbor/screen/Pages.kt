package podbor.screen

import podbor.core.ScanBoard

/** Страница под сетью: добавить подписку, анализ подписки, главный экран. */
sealed interface Page {
    data object AddSubscription : Page
    data class Scanning(val board: ScanBoard?) : Page
    data object Main : Page
}

/**
 * Что с анализом подписки: не идёт; человек выбрал «Сменить подписку»; идёт (доски ещё нет — null); кончился, а «ОК» не
 * нажато; не принялся (ошибка — словами на странице подписки).
 */
sealed interface ScanPhase {
    data object Idle : ScanPhase
    data object Choosing : ScanPhase
    data class Running(val board: ScanBoard?) : ScanPhase
    data class Done(val board: ScanBoard) : ScanPhase
    data class Failed(val error: InputError) : ScanPhase
}

/** Страница по фактам экрана: анализ виден, пока его не закрыли «ОК»; без подписки — только «Добавь подписку». */
fun pageOf(hasSubscription: Boolean, scan: ScanPhase): Page = when (scan) {
    is ScanPhase.Running -> Page.Scanning(scan.board)
    is ScanPhase.Done -> Page.Scanning(scan.board)
    ScanPhase.Choosing, is ScanPhase.Failed -> Page.AddSubscription
    ScanPhase.Idle -> if (hasSubscription) Page.Main else Page.AddSubscription
}

/** Ошибка, которую страница подписки показывает словами; нет — null. */
fun errorOf(scan: ScanPhase): InputError? = when (scan) {
    is ScanPhase.Failed -> scan.error
    ScanPhase.Idle, ScanPhase.Choosing, is ScanPhase.Running, is ScanPhase.Done -> null
}
