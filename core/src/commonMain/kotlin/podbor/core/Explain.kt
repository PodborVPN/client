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
    val lineEvidence: Evidence,
    val tried: Map<CandidateClass, Map<VerdictKind, Int>>,
    val skipped: Map<CandidateClass, Map<SkipReason, Int>>,
    val outcome: Outcome,
    /** Когда нажатие кончилось — время письма, на котором машина пришла в Done. */
    val finish: Finish,
)

/** Раунд линии, по которому выбран сценарий нажатия: ещё не было или был. */
sealed interface Evidence {
    data object None : Evidence
    data class Round(val observed: Letter.LineObserved) : Evidence
}

/** Конец нажатия: ещё идёт или кончилось в момент [At.atMs]. */
sealed interface Finish {
    data object NotYet : Finish
    data class At(val atMs: Long) : Finish
}

/**
 * Чистая и тотальная: для любой последовательности букв даёт объяснение последнего нажатия.
 * Свёртка прогоняет саму машину step: что запущено, какой ответ принят и кончилось ли нажатие —
 * её решения, а не пересказ. Поэтому экран и машина не могут разойтись.
 */
fun explain(letters: List<Letter>): Explanation {
    val press = letters.drop(letters.indexOfLast { it is Letter.Pressed }.coerceAtLeast(0))
    val pressed = press.firstOrNull()
    if (pressed !is Letter.Pressed) return Explanation(Diagnosis.Pending, Evidence.None, emptyMap(), emptyMap(), Outcome.InProgress, Finish.NotYet)
    val trace = replay(press)
    val byId = pressed.servers.associateBy { it.id }
    val diagnosis = when (val st = trace.state) {
        is PressState.Running -> st.diagnosis
        is PressState.Done -> st.diagnosis
        PressState.Idle -> Diagnosis.Pending
    }
    // Итог сервера — его последний принятый ответ: повтор не считается вторым сервером.
    val finals = trace.accepted.associateBy { it.server }.values.toList()
    val tried = finals.groupBy { classOf(byId.getValue(it.server)) }
        .mapValues { (_, as_) -> as_.groupingBy { it.verdict.kind() }.eachCount() }

    val silenced = finals.filter { it.verdict.kind() == VerdictKind.TlsSilence }.map { byId.getValue(it.server).endpoint }.toSet()
    val planAtMs = when (val e = trace.line) {
        Evidence.None -> pressed.atMs
        is Evidence.Round -> e.observed.atMs
    }
    val planned = diagnosis.scene.fold({ SearchPlan(emptyList(), emptyMap()) }) {
        plan(it, pressed.servers, pressed.memory, memoryKey(pressed.os, it, pressed.service), planAtMs, pressed.hints)
    }
    // Двойники по IP:порт, выброшенные машиной после TlsSilence до запуска, — пропущены, а не «ещё в работе».
    val (twins, admissible) = planned.queue.partition { it.id !in trace.launched && it.endpoint in silenced }
    val skipped = mergeSkips(
        planned.skipped,
        twins.groupBy(::classOf).mapValues { (_, s) -> mapOf<SkipReason, Int>(SkipReason.BlockedRecently to s.size) },
    )

    val outcome = when (val st = trace.state) {
        PressState.Idle, is PressState.Running -> Outcome.InProgress
        is PressState.Done -> doneOf(trace, st.diagnosis.scene, admissible.size)
    }
    return Explanation(diagnosis, trace.line, tried, skipped, outcome, trace.finish)
}

/** Нажатие кончилось: выход — каким по счёту ответил; без выхода — вывод по сценарию. */
private fun doneOf(trace: Trace, scene: Scene, admissible: Int): Outcome = when (val hit = trace.found) {
    Hit.None -> scene.fold({ Outcome.InProgress }) { notFoundOf(it, noneAdmissible = admissible == 0) }
    is Hit.Fits -> Outcome.Found(
        hit.answer.server, position = trace.accepted.map { it.server }.distinct().indexOf(hit.answer.server) + 1, admissible = admissible,
    )
}

/** Нажатие кончилось без выхода: вывод — по сценарию и тому, было ли вообще что пробовать. */
private fun notFoundOf(scenario: Scenario, noneAdmissible: Boolean): Outcome = when (scenario) {
    Scenario.Offline -> Outcome.NotFound(Conclusion.NoNetwork)
    Scenario.MobileWhitelist, Scenario.SuspectWhitelist ->
        Outcome.NotFound(if (noneAdmissible) Conclusion.NoAdmissibleInConfig else Conclusion.AllAdmissibleFailed)
    Scenario.MobileOpen, Scenario.Wifi, Scenario.Unclear ->
        Outcome.NotFound(if (noneAdmissible) Conclusion.NoAdmissibleInConfig else Conclusion.NothingWorksOpen)
}

/** След прогона машины по буквам одного нажатия. */
private data class Trace(
    val state: PressState,
    /** Ответы, которые машина приняла (сервер был в полёте), в порядке прихода. */
    val accepted: List<Letter.ProbeAnswered>,
    val launched: Set<ServerId>,
    /** Линия, по которой машина последний раз выбрала сценарий. */
    val line: Evidence,
    val found: Hit,
    val finish: Finish,
)

/** Ответ, которым нажатие нашло выход: ещё нет или он. */
private sealed interface Hit {
    data object None : Hit
    data class Fits(val answer: Letter.ProbeAnswered) : Hit
}

private fun replay(press: List<Letter>): Trace =
    press.fold(Trace(PressState.Idle, emptyList(), emptySet(), Evidence.None, Hit.None, Finish.NotYet)) { t, letter ->
        val before = t.state
        val (next, deeds) = step(before, letter)
        val accepted = letter is Letter.ProbeAnswered && inFlight(before, letter.server)
        Trace(
            state = next,
            accepted = if (accepted) t.accepted + letter else t.accepted,
            launched = t.launched + deeds.filterIsInstance<Deed.Probe>().map { it.server },
            line = when {
                letter is Letter.LineObserved && diagnosing(before) -> Evidence.Round(letter)
                letter is Letter.Pressed -> lastFreshRound(letter)
                else -> t.line
            },
            found = if (accepted && next is PressState.Done && letter.verdict.kind() == VerdictKind.Fits) Hit.Fits(letter) else t.found,
            finish = when {
                t.finish is Finish.At -> t.finish
                next is PressState.Done -> Finish.At(letter.atMs)
                else -> Finish.NotYet
            },
        )
    }

/** Ответ принят машиной ⟺ сервер был в полёте. */
private fun inFlight(s: PressState, server: ServerId): Boolean = when (s) {
    is PressState.Running -> s.inFlight.any { it.id == server }
    PressState.Idle, is PressState.Done -> false
}

/** Сценарий ещё выбирается: раунд линии войдёт в диагноз. */
private fun diagnosing(s: PressState): Boolean = when (s) {
    is PressState.Running -> s.diagnosis !is Diagnosis.Settled
    PressState.Idle, is PressState.Done -> false
}

/** Свежий раунд детектора, с которым пришло нажатие; нет свежих — нет. */
private fun lastFreshRound(p: Letter.Pressed): Evidence = when (val prior = p.freshPrior()) {
    Prior.None -> Evidence.None
    is Prior.Detected -> Evidence.Round(prior.rounds.last())
}

private fun mergeSkips(
    a: Map<CandidateClass, Map<SkipReason, Int>>,
    b: Map<CandidateClass, Map<SkipReason, Int>>,
): Map<CandidateClass, Map<SkipReason, Int>> =
    (a.keys + b.keys).associateWith { c ->
        val x = a[c].orEmpty(); val y = b[c].orEmpty()
        (x.keys + y.keys).associateWith { r -> x.getOrElse(r) { 0 } + y.getOrElse(r) { 0 } }
    }
