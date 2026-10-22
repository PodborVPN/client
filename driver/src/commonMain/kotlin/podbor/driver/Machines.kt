package podbor.driver

import podbor.core.Deed
import podbor.core.Letter
import podbor.core.OpenState
import podbor.core.PressState
import podbor.core.ScanState
import podbor.core.ServiceId
import podbor.core.openStart
import podbor.core.openStep
import podbor.core.scanStep
import podbor.core.settled
import podbor.core.step as pressStep

/** Машина нажатия для актора: начинает с Idle, готова на Done. [service] — только в шапку: сервис несёт Pressed. */
internal class PressMachine(service: ServiceId) : Machine<PressState> {
    override val head = Head.Press(service)
    override fun start(first: Letter) = pressStep(PressState.Idle, first)
    override fun step(s: PressState, l: Letter) = pressStep(s, l)
    override fun done(s: PressState) = s is PressState.Done
}

/**
 * Машина открытия для актора: первая буква — та самая Opened, по которой машина собрана. Раунды [known] уже сняты —
 * сворачиваются в состояние на старте; свой первый ProbeLine тогда не нужен (подтверждающий машина попросит сама).
 */
internal class OpenMachine(
    private val opened: Letter.Opened,
    private val known: List<Letter.LineObserved>,
    service: ServiceId,
) : Machine<OpenState> {
    override val head = Head.Open(known, service)
    override fun start(first: Letter): Pair<OpenState, List<Deed>> {
        val (s, deeds) = openStart(opened)
        if (known.isEmpty()) return s to deeds
        return known.fold(s to deeds.filterNot { it == Deed.ProbeLine }) { (state, acc), l ->
            val (next, more) = openStep(state, l)
            next to acc + more
        }
    }

    override fun step(s: OpenState, l: Letter) = openStep(s, l)
    override fun done(s: OpenState) = s.settled()
}

/** Машина анализа для актора: начинает с Idle (первая буква — Scan), готова на Done. */
internal object ScanMachine : Machine<ScanState> {
    override val head = Head.Scan
    override fun start(first: Letter) = scanStep(ScanState.Idle, first)
    override fun step(s: ScanState, l: Letter) = scanStep(s, l)
    override fun done(s: ScanState) = s is ScanState.Done
}
