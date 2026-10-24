package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.core.NetworkMove
import podbor.core.OsNetwork
import podbor.core.Tower

class NetworkMovesTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val megafon = OsNetwork.Cellular("MegaFon", "25002")

    /** Сеть ОС и подъём сессии — руками; [moves] — что выдала цепочка. */
    class Rig(scope: TestScope, os: OsNetwork = OsNetwork.Wifi, up: Boolean = true) {
        val os = MutableStateFlow(os)
        val up = MutableStateFlow(up)
        val moves = mutableListOf<NetworkMove>()
        val job = scope.backgroundScope.launch { networkMoves(this@Rig.os, this@Rig.up).collect { moves += it } }
    }

    fun TestScope.settle() {
        advanceTimeBy(NETWORK_SETTLE_MS + 1)
        runCurrent()
    }

    test("Wi-Fi → LTE на поднятой сессии — один переезд, когда сеть устоялась") {
        runTest {
            val rig = Rig(this)
            settle()
            rig.os.value = mts
            advanceTimeBy(NETWORK_SETTLE_MS - 1)
            runCurrent()
            rig.moves shouldBe emptyList()
            settle()
            rig.moves shouldBe listOf(NetworkMove(OsNetwork.Wifi, mts))
        }
    }

    test("смена симки данных: МТС → Мегафон — переезд") {
        runTest {
            val rig = Rig(this, os = mts)
            settle()
            rig.os.value = megafon
            settle()
            rig.moves shouldBe listOf(NetworkMove(mts, megafon))
        }
    }

    test("тот же ключ — не переезд: другая вышка того же оператора") {
        runTest {
            val rig = Rig(this, os = mts)
            settle()
            rig.os.value = mts.copy(tower = Tower.Known(1575, 1300, -103))
            settle()
            rig.moves shouldBe emptyList()
        }
    }

    test("мигание Wi-Fi → LTE → Wi-Fi быстрее, чем устоялось, — не переезд") {
        runTest {
            val rig = Rig(this)
            settle()
            rig.os.value = mts
            advanceTimeBy(NETWORK_SETTLE_MS / 2)
            rig.os.value = OsNetwork.Wifi
            settle()
            rig.moves shouldBe emptyList()
        }
    }

    test("нет сети — не переезд: ждём ключа, сравниваем с последней сетью") {
        runTest {
            val rig = Rig(this)
            settle()
            rig.os.value = OsNetwork.None
            settle()
            rig.moves shouldBe emptyList()
            rig.os.value = OsNetwork.Wifi
            settle()
            rig.moves shouldBe emptyList()
            rig.os.value = OsNetwork.None
            settle()
            rig.os.value = mts
            settle()
            rig.moves shouldBe listOf(NetworkMove(OsNetwork.Wifi, mts))
        }
    }

    test("сессия не поднята — переездов нет; поднялась — отсчёт от сети на подъёме") {
        runTest {
            val rig = Rig(this, up = false)
            settle()
            rig.os.value = mts
            settle()
            rig.moves shouldBe emptyList()
            rig.up.value = true
            settle()
            rig.moves shouldBe emptyList()
            rig.os.value = OsNetwork.Wifi
            settle()
            rig.moves shouldBe listOf(NetworkMove(mts, OsNetwork.Wifi))
        }
    }

    test("сессия опустилась посреди — переезд, начатый до опускания, не выдаётся") {
        runTest {
            val rig = Rig(this)
            settle()
            rig.os.value = mts
            runCurrent()
            rig.up.value = false
            settle()
            rig.moves shouldBe emptyList()
        }
    }
})
