package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import podbor.app.net.CellSnapshot
import podbor.app.net.Radio
import podbor.app.net.Transport
import podbor.app.net.osNetworkOf
import podbor.app.net.towerOf
import podbor.core.OsNetwork
import podbor.core.Tower

class TelephonyTest : FunSpec({

    test("нет разрешения или данных (null) → Tower.Unknown") {
        towerOf(null) shouldBe Tower.Unknown
    }

    test("зарегистрированная LTE-ячейка → Known") {
        towerOf(CellSnapshot(cellId = 123_456_789L, earfcn = 1300, rsrp = -95)) shouldBe Tower.Known(123_456_789L, 1300, -95)
    }

    test("границы RSRP −140 и −44 — ещё Known") {
        towerOf(CellSnapshot(1L, 100, -140)) shouldBe Tower.Known(1L, 100, -140)
        towerOf(CellSnapshot(1L, 100, -44)) shouldBe Tower.Known(1L, 100, -44)
    }

    context("недоступные значения Android (CellInfo.UNAVAILABLE = Int.MAX_VALUE) и RSRP вне −140..−44 → Unknown") {
        withData(
            nameFn = { it.toString() },
            CellSnapshot(1L, 100, Int.MAX_VALUE),
            CellSnapshot(1L, 100, -43),
            CellSnapshot(1L, 100, -141),
            CellSnapshot(1L, Int.MAX_VALUE, -95),
            CellSnapshot(Int.MAX_VALUE.toLong(), 100, -95),
        ) { towerOf(it) shouldBe Tower.Unknown }
    }

    test("сотовая → Cellular с оператором, MCC/MNC и вышкой") {
        val tower = Tower.Known(1L, 1300, -95)
        osNetworkOf(Transport.Cellular, "MTS RUS", "25001", tower) shouldBe OsNetwork.Cellular("MTS RUS", "25001", tower)
    }

    test("сотовая без имени оператора и без MCC/MNC → «?»") {
        osNetworkOf(Transport.Cellular, " ", null, Tower.Unknown) shouldBe OsNetwork.Cellular("?", "?", Tower.Unknown)
    }

    test("Wi-Fi и нет сети — без оператора и вышки") {
        osNetworkOf(Transport.Wifi, "MTS RUS", "25001", Tower.Known(1L, 1, -90)) shouldBe OsNetwork.Wifi
        osNetworkOf(Transport.None, null, null, Tower.Unknown) shouldBe OsNetwork.None
    }

    test("имя оператора — от SIM данных: не меняется с отображаемым именем сети (5G/LTE) и в роуминге") {
        val sim = Radio(simName = "MTS RUS", simMccMnc = "25001", networkName = "MTS 5G", networkMccMnc = "25001")
        val expected = OsNetwork.Cellular("MTS RUS", "25001", Tower.Unknown)
        osNetworkOf(Transport.Cellular, sim, Tower.Unknown) shouldBe expected
        osNetworkOf(Transport.Cellular, sim.copy(networkName = "MTS LTE"), Tower.Unknown) shouldBe expected
        osNetworkOf(Transport.Cellular, sim.copy(networkName = "Beeline", networkMccMnc = "25099"), Tower.Unknown) shouldBe expected
    }

    test("SIM без имени → MCC/MNC SIM, а не имя сети") {
        osNetworkOf(Transport.Cellular, Radio(null, "25001", "MTS 5G", "25001"), Tower.Unknown) shouldBe
            OsNetwork.Cellular("25001", "25001", Tower.Unknown)
    }
})
