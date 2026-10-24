package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.HistoryEvent
import podbor.core.Mood
import podbor.core.Telegram

/** История строки для шторки: по метке строки — сервис карточки, у сервиса — его история. */
class HistoryVmTest : FunSpec({

    test("по метке — сервис карточки, у порта — его история; сервиса или порта нет — пусто") {
        vmTest {
            val vm = vm()
            vm.history("TG") shouldBe emptyList()
            val ev = listOf(HistoryEvent(1, Mood.Good, "MTS · «NL-7» работает"))
            vm.attach(FakePort().apply { histories = mapOf(Telegram.id to ev) })
            vm.history("TG") shouldBe ev
            vm.history("IG") shouldBe emptyList()
            vm.history("??") shouldBe emptyList()
        }
    }
})
