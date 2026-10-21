package podbor.core

/** Вывод нажатия без выхода — тоже сумма: у каждого свои слова. */
enum class Conclusion { NoAdmissibleInConfig, AllAdmissibleFailed, NoNetwork, NothingWorksOpen }

sealed interface Outcome {
    data object InProgress : Outcome
    /** Выход найден: position — каким по счёту ответил, admissible — сколько было допустимых. */
    data class Found(val server: ServerId, val position: Int, val admissible: Int) : Outcome
    data class NotFound(val conclusion: Conclusion) : Outcome
}

/** Свёртка истории нажатия. Строится только из вердиктов, которые ядро реально получило. */
data class Explanation(
    /** Диагноз линии; подтверждающий раунд ещё снимается ⟺ Suspected. */
    val diagnosis: Diagnosis,
    val lineEvidence: LineObservation?,
    val tried: Map<CandidateClass, Map<VerdictKind, Int>>,
    val skipped: Map<CandidateClass, Map<SkipReason, Int>>,
    val outcome: Outcome,
)

/**
 * Чистая и тотальная: для любой последовательности букв даёт объяснение последнего нажатия.
 * Свёртка прогоняет саму машину step: что запущено, какой ответ принят и кончилось ли нажатие —
 * её решения, а не пересказ. Поэтому экран и машина не могут разойтись.
 */
fun explain(letters: List<Letter>): Explanation {
    val press = letters.drop(letters.indexOfLast { it is Letter.Pressed }.coerceAtLeast(0))
    val pressed = press.firstOrNull() as? Letter.Pressed
        ?: return Explanation(Diagnosis.Pending, null, emptyMap(), emptyMap(), Outcome.InProgress)
    val trace = replay(press)
    val byId = pressed.servers.associateBy { it.id }
    val diagnosis = when (val st = trace.state) {
        is PressState.Running -> st.diagnosis
        is PressState.Done -> st.diagnosis
        PressState.Idle -> Diagnosis.Pending
    }
    val scenario = diagnosis.scenario
    // Итог сервера — его последний принятый ответ: повтор не считается вторым сервером.
    val finals = trace.accepted.associateBy { it.server }.values.toList()
    val tried = finals.groupBy { classOf(byId.getValue(it.server)) }
        .mapValues { (_, as_) -> as_.groupingBy { it.verdict.kind() }.eachCount() }

    val silenced = finals.filter { it.verdict.kind() == VerdictKind.TlsSilence }.map { byId.getValue(it.server).endpoint }.toSet()
    val planned = scenario?.let { plan(it, pressed.servers, pressed.memory, memoryKey(pressed.os, it, pressed.service), trace.line?.atMs ?: pressed.atMs, pressed.hints) }
    // Двойники по IP:порт, выброшенные машиной после TlsSilence до запуска, — пропущены, а не «ещё в работе».
    val (twins, admissible) = planned?.queue.orEmpty().partition { it.id !in trace.launched && it.endpoint in silenced }
    val skipped = mergeSkips(
        planned?.skipped.orEmpty(),
        twins.groupBy(::classOf).mapValues { (_, s) -> mapOf<SkipReason, Int>(SkipReason.BlockedRecently to s.size) },
    )

    val outcome = when (val st = trace.state) {
        PressState.Idle, is PressState.Running -> Outcome.InProgress
        is PressState.Done -> trace.found?.let { f ->
            Outcome.Found(f.server, position = trace.accepted.map { it.server }.distinct().indexOf(f.server) + 1, admissible = admissible.size)
        } ?: when (st.diagnosis.scenario) {
            null -> Outcome.InProgress
            Scenario.Offline -> Outcome.NotFound(Conclusion.NoNetwork)
            Scenario.MobileWhitelist, Scenario.SuspectWhitelist ->
                Outcome.NotFound(if (admissible.isEmpty()) Conclusion.NoAdmissibleInConfig else Conclusion.AllAdmissibleFailed)
            Scenario.MobileOpen, Scenario.Wifi, Scenario.Unclear ->
                Outcome.NotFound(if (admissible.isEmpty()) Conclusion.NoAdmissibleInConfig else Conclusion.NothingWorksOpen)
        }
    }
    return Explanation(diagnosis, trace.line?.line, tried, skipped, outcome)
}

/** След прогона машины по буквам одного нажатия. */
private data class Trace(
    val state: PressState,
    /** Ответы, которые машина приняла (сервер был в полёте), в порядке прихода. */
    val accepted: List<Letter.ProbeAnswered>,
    val launched: Set<ServerId>,
    /** Линия, по которой машина последний раз выбрала сценарий. */
    val line: Letter.LineObserved?,
    val found: Letter.ProbeAnswered?,
)

private fun replay(press: List<Letter>): Trace =
    press.fold(Trace(PressState.Idle, emptyList(), emptySet(), null, null)) { t, letter ->
        val before = t.state
        val (next, deeds) = step(before, letter)
        val running = before as? PressState.Running
        val accepted = letter is Letter.ProbeAnswered && running?.inFlight?.any { it.id == letter.server } == true
        Trace(
            state = next,
            accepted = if (accepted) t.accepted + letter else t.accepted,
            launched = t.launched + deeds.filterIsInstance<Deed.Probe>().map { it.server },
            line = when {
                letter is Letter.LineObserved && running != null && running.diagnosis !is Diagnosis.Settled -> letter
                letter is Letter.Pressed -> when (val prior = letter.freshPrior()) {
                    Prior.None -> null
                    is Prior.Detected -> prior.rounds.last()
                }
                else -> t.line
            },
            found = if (accepted && next is PressState.Done && letter.verdict.kind() == VerdictKind.Fits) letter else t.found,
        )
    }

private fun mergeSkips(
    a: Map<CandidateClass, Map<SkipReason, Int>>,
    b: Map<CandidateClass, Map<SkipReason, Int>>,
): Map<CandidateClass, Map<SkipReason, Int>> =
    (a.keys + b.keys).associateWith { c ->
        val x = a[c].orEmpty(); val y = b[c].orEmpty()
        (x.keys + y.keys).associateWith { r -> (x[r] ?: 0) + (y[r] ?: 0) }
    }
