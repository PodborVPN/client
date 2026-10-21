package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/** Таблица сценариев для одного раунда: ОС — только априори, решает линия; белые списки с одного раунда — лишь подозрение. */
class ScenarioTest : FunSpec({

    data class Row(val os: OsNetwork, val russia: Reach, val abroad: Reach, val expected: Scenario)

    val mts = OsNetwork.Cellular(operator = "MTS", mccMnc = "25001")

    withData(
        nameFn = { "${it.os} РФ=${it.russia} заграница=${it.abroad} → ${it.expected}" },
        Row(mts, Reach.Up, Reach.Up, Scenario.MobileOpen),
        Row(mts, Reach.Up, Reach.Down, Scenario.SuspectWhitelist),
        Row(OsNetwork.Wifi, Reach.Up, Reach.Up, Scenario.Wifi),
        Row(OsNetwork.Wifi, Reach.Up, Reach.Down, Scenario.SuspectWhitelist),
        Row(mts, Reach.Down, Reach.Down, Scenario.Offline),
        Row(OsNetwork.Wifi, Reach.Down, Reach.Down, Scenario.Offline),
        // Заграница отвечает рукопожатием — сеть открыта, что бы ни было с российскими эталонами.
        Row(mts, Reach.Down, Reach.Up, Scenario.MobileOpen),
        Row(OsNetwork.Wifi, Reach.Down, Reach.Up, Scenario.Wifi),
        // Review Focus 4: ОС говорит «нет сети», но линию всё равно снимаем и верим ей.
        Row(OsNetwork.None, Reach.Up, Reach.Up, Scenario.MobileOpen),
        Row(OsNetwork.None, Reach.Down, Reach.Down, Scenario.Offline),
    ) { row -> scenarioOf(row.os, nonEmptyListOf(line(row.russia, row.abroad))) shouldBe row.expected }
})
