package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/** Диагноз линии по раундам: подозрение ждёт второй раунд, итог больше не меняется. */
class DiagnosisTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val wl = line(Reach.Up, Reach.Down)
    val open = line(Reach.Up, Reach.Up)
    val murky = LineObservation(
        russia = nonEmptyListOf(0, 1, 2).map { Sample("ru$it", Handshake.Ok(30)) },
        abroad = nonEmptyListOf(0, 1, 2).map { Sample("far$it", Handshake.Unknown("dns")) },
    )
    val settled = Diagnosis.Settled(Scenario.MobileOpen, nonEmptyListOf(open))

    data class Row(val name: String, val before: Diagnosis, val line: LineObservation, val after: Diagnosis, val os: OsNetwork = mts)

    withData(
        nameFn = { it.name },
        Row("ждём + «похоже» → подозрение", Diagnosis.Pending, wl, Diagnosis.Suspected(wl)),
        Row("ждём + открыто → открыто с одним раундом", Diagnosis.Pending, open, Diagnosis.Settled(Scenario.MobileOpen, nonEmptyListOf(open))),
        Row("подозрение + «похоже» → белые списки", Diagnosis.Suspected(wl), wl, Diagnosis.Settled(Scenario.MobileWhitelist, nonEmptyListOf(wl, wl))),
        Row("подозрение + открыто → открыто", Diagnosis.Suspected(wl), open, Diagnosis.Settled(Scenario.MobileOpen, nonEmptyListOf(wl, open))),
        Row("подозрение + непонятно → подозрение остаётся итогом", Diagnosis.Suspected(wl), murky,
            Diagnosis.Settled(Scenario.SuspectWhitelist, nonEmptyListOf(wl, murky))),
        Row("ждём + непонятно → непонятно, без второго раунда", Diagnosis.Pending, murky, Diagnosis.Settled(Scenario.Unclear, nonEmptyListOf(murky))),
        Row("белый список, увиденный линией на Wi-Fi (раздача), — тоже белый список", Diagnosis.Suspected(wl), wl,
            Diagnosis.Settled(Scenario.MobileWhitelist, nonEmptyListOf(wl, wl)), os = OsNetwork.Wifi),
        Row("итог + поздний раунд «похоже» → итог не меняется", settled, wl, settled),
        Row("итог + поздний раунд открыто → итог не меняется", settled, open, settled),
        Row("перепроверка + «похоже» → подозрение по новому раунду", Diagnosis.Rechecking(settled), wl, Diagnosis.Suspected(wl)),
        Row("перепроверка + открыто → итог только по новому раунду", Diagnosis.Rechecking(settled), open,
            Diagnosis.Settled(Scenario.MobileOpen, nonEmptyListOf(open))),
        Row("перепроверка + непонятно → непонятно, прежний итог не в счёт", Diagnosis.Rechecking(settled), murky,
            Diagnosis.Settled(Scenario.Unclear, nonEmptyListOf(murky))),
    ) { row -> row.before.observe(row.os, row.line) shouldBe row.after }

    test("сценарий для экрана: ждём — ещё нет, подозрение — подозрение, итог — свой, перепроверка — прежний") {
        Diagnosis.Pending.scene shouldBe Scene.Pending
        Diagnosis.Rechecking(Diagnosis.Settled(Scenario.Wifi, nonEmptyListOf(open))).scene shouldBe Scene.Chosen(Scenario.Wifi)
        Diagnosis.Suspected(wl).scene shouldBe Scene.Chosen(Scenario.SuspectWhitelist)
        settled.scene shouldBe Scene.Chosen(Scenario.MobileOpen)
        Scene.Pending.whitelist shouldBe false
        Scene.Chosen(Scenario.SuspectWhitelist).whitelist shouldBe true
        Scene.Chosen(Scenario.Wifi).whitelist shouldBe false
    }
})
