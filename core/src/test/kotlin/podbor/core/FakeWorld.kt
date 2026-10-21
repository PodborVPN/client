package podbor.core

/** Мир по сценарию: линия (можно сменить после N-го ответа пробы) и вердикт на сервер. */
data class FakeWorld(
    val line: LineObservation,
    val verdicts: Map<ServerId, Verdict>,
    val lineAfter: Pair<Int, LineObservation>? = null,
)

data class Run(val letters: List<Letter>, val deeds: List<Deed>, val probed: List<ServerId>, val maxInFlightPerEndpoint: Int)

/**
 * Петля «дела → ответы мира → буквы» до Done; каждый ответ +500 мс, ответы FIFO.
 * Нет вердикта в карте → Unreachable. Так край будет кормить ядро в :driver.
 */
fun runPress(pressed: Letter.Pressed, world: FakeWorld): Run {
    val byId = pressed.servers.associateBy { it.id }
    val pending = ArrayDeque<(Long) -> Letter>()
    val letters = mutableListOf<Letter>()
    val deeds = mutableListOf<Deed>()
    val probed = mutableListOf<ServerId>()
    val inFlight = mutableMapOf<Endpoint, Int>()
    var maxPerEndpoint = 0
    var answers = 0
    var now = pressed.atMs
    var state: PressState = PressState.Idle

    fun feed(letter: Letter) {
        letters += letter
        val (next, out) = step(state, letter)
        state = next
        deeds += out
        out.forEach { d ->
            when (d) {
                Deed.ProbeLine -> pending += { t ->
                    val line = world.lineAfter?.takeIf { (n, _) -> answers >= n }?.second ?: world.line
                    Letter.LineObserved(t, line)
                }
                is Deed.Probe -> {
                    probed += d.server
                    val e = byId.getValue(d.server).endpoint
                    inFlight[e] = (inFlight[e] ?: 0) + 1
                    maxPerEndpoint = maxOf(maxPerEndpoint, inFlight.getValue(e))
                    pending += { t ->
                        answers += 1
                        inFlight[e] = inFlight.getValue(e) - 1
                        Letter.ProbeAnswered(t, d.server, world.verdicts[d.server] ?: Verdict.Unreachable)
                    }
                }
                // Нажатие их не выдаёт: это дела анализа.
                is Deed.MakeExit, is Deed.MeasureSpeed, is Deed.Remember, is Deed.MeasureExit, is Deed.SavePassport -> Unit
            }
        }
    }

    feed(pressed)
    while (state !is PressState.Done && pending.isNotEmpty() && letters.size < 1_000) {
        now += 500
        feed(pending.removeFirst()(now))
    }
    return Run(letters, deeds, probed, maxPerEndpoint)
}
