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

/** Сценарий диагноза для экрана и памяти: ещё не выбран (ждём первый раунд) или выбран. */
sealed interface Scene {
    data object Pending : Scene
    data class Chosen(val scenario: Scenario) : Scene
}

/** Сценарий диагноза: подозрение — уже белые списки, перепроверка держит прежний. */
val Diagnosis.scene: Scene
    get() = when (this) {
        Diagnosis.Pending -> Scene.Pending
        is Diagnosis.Suspected -> Scene.Chosen(Scenario.SuspectWhitelist)
        is Diagnosis.Settled -> Scene.Chosen(scenario)
        is Diagnosis.Rechecking -> previous.scene
    }

fun <T> Scene.fold(pending: () -> T, chosen: (Scenario) -> T): T = when (this) {
    Scene.Pending -> pending()
    is Scene.Chosen -> chosen(scenario)
}

/** Сценарий выбран и он — белые списки или подозрение на них: перебор входов РФ. */
val Scene.whitelist: Boolean
    get() = fold({ false }) { it == Scenario.MobileWhitelist || it == Scenario.SuspectWhitelist }
