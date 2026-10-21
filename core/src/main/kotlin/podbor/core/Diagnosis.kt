package podbor.core

import arrow.core.nonEmptyListOf

/**
 * Диагноз линии в одном нажатии — сумма вместо фазы и флагов.
 * Перепроверка посреди перебора начинает диагноз заново (Rechecking): старые раунды в новый не идут.
 */
sealed interface Diagnosis {
    /** Ждём первый раунд; перебор стоит. */
    data object Pending : Diagnosis

    /** Первый раунд похож на белые списки: перебор входов РФ идёт, ждём подтверждающий раунд. */
    data class Suspected(val first: LineObservation) : Diagnosis

    /** Сценарий выбран по раундам этого диагноза; поздние раунды его не меняют. */
    data class Settled(val scenario: Scenario, val rounds: Nel<LineObservation>) : Diagnosis

    /** Перепроверка посреди перебора: ждём новый раунд, прежний сценарий в силе до него. */
    data class Rechecking(val previous: Settled) : Diagnosis
}

/** Раунд линии в диагноз: «похоже» первым — подозрение, второй раунд решает; итог неизменен. */
fun Diagnosis.observe(os: OsNetwork, line: LineObservation): Diagnosis = when (this) {
    // Перепроверка судит только по новым раундам — как с чистого листа.
    Diagnosis.Pending, is Diagnosis.Rechecking -> {
        val rounds = nonEmptyListOf(line)
        if (needsConfirmation(rounds)) Diagnosis.Suspected(line) else Diagnosis.Settled(scenarioOf(os, rounds), rounds)
    }
    is Diagnosis.Suspected -> nonEmptyListOf(first, line).let { Diagnosis.Settled(scenarioOf(os, it), it) }
    is Diagnosis.Settled -> this
}

/** Единственное место, где «сценария ещё нет» — null: граница к экрану и памяти. Перепроверка держит прежний. */
val Diagnosis.scenario: Scenario?
    get() = when (this) {
        Diagnosis.Pending -> null
        is Diagnosis.Suspected -> Scenario.SuspectWhitelist
        is Diagnosis.Settled -> scenario
        is Diagnosis.Rechecking -> previous.scenario
    }
