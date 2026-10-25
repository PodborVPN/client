package podbor.screen

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent

/** Список подписок глазами ViewModel: шторка карточки, выбор с обновлением, удаление, переанализ. */
class SubscriptionsVmTest : FunSpec({
    val other = CARD.copy(id = "b", title = "вторая")
    fun two() = FakeShelf(Subscriptions.Chosen(CARD, listOf(other)))

    test("тап по карточке — шторка; у текущей и прочей — своя отметка") {
        vmTest {
            val vm = vm(shelf = two())
            vm.attach(FakePort())
            runCurrent()
            vm.openCard(other)
            vm.sheet.value shouldBe SubSheet.Card(other, current = false)
            vm.openCard(CARD)
            vm.sheet.value shouldBe SubSheet.Card(CARD, current = true)
            vm.closeSheet()
            vm.sheet.value shouldBe null
        }
    }

    test("выбрать прочую: пока сервис обновляет — «обновляю»; готово — шторки нет, главный и открытие") {
        vmTest {
            val shelf = two()
            val vm = vm(shelf = shelf)
            val port = FakePort()
            val gate = CompletableDeferred<Picked>()
            port.picked = { gate.await() }
            vm.attach(port)
            runCurrent()
            vm.changeSubscription()
            vm.pick(other)
            runCurrent()
            vm.sheet.value shouldBe SubSheet.Picking(other)
            shelf.subs = Subscriptions.Chosen(other, listOf(CARD))
            gate.complete(Picked.Ready)
            runCurrent()
            port.selected shouldBe listOf("b")
            vm.sheet.value shouldBe null
            vm.page.value shouldBe Page.Main
            vm.subscriptions.value shouldBe Subscriptions.Chosen(other, listOf(CARD))
            port.opens shouldBe 1
        }
    }

    test("выбрать протухшую — шторка «кончилась» с поддержкой, страница подписок, без открытия") {
        vmTest {
            val vm = vm(shelf = two())
            val port = FakePort()
            port.picked = { Picked.Expired(Support.Link("https://t.me/help")) }
            vm.attach(port)
            runCurrent()
            vm.changeSubscription()
            vm.pick(other)
            runCurrent()
            vm.sheet.value shouldBe SubSheet.Ended(other, Support.Link("https://t.me/help"))
            vm.page.value shouldBe Page.AddSubscription
            port.opens shouldBe 0
        }
    }

    test("удаление текущей при поднятом VPN — подтверждение «VPN выключится»; при выключенном и у прочей — без") {
        vmTest {
            val vm = vm(shelf = two())
            val port = FakePort()
            vm.attach(port)
            runCurrent()
            vm.askDelete(CARD)
            vm.sheet.value shouldBe SubSheet.Confirm(CARD, stopsVpn = false)
            port.session.value = SessionView.Up
            vm.askDelete(CARD)
            vm.sheet.value shouldBe SubSheet.Confirm(CARD, stopsVpn = true)
            port.session.value = SessionView.Starting
            vm.askDelete(CARD)
            vm.sheet.value shouldBe SubSheet.Confirm(CARD, stopsVpn = true)
            vm.askDelete(other)
            vm.sheet.value shouldBe SubSheet.Confirm(other, stopsVpn = false)
        }
    }

    test("удалить текущую при других — шторки нет, текущей нет, «назад» к главному нет; сами не выбираем") {
        vmTest {
            val shelf = two()
            val vm = vm(shelf = shelf)
            val port = FakePort()
            port.onDelete = { shelf.subs = Subscriptions.Unchosen(nonEmptyListOf(other)) }
            vm.attach(port)
            runCurrent()
            vm.changeSubscription()
            vm.confirmDelete(CARD)
            runCurrent()
            port.deleted shouldBe listOf("a")
            port.selected shouldBe emptyList()
            vm.sheet.value shouldBe null
            vm.subscriptions.value shouldBe Subscriptions.Unchosen(nonEmptyListOf(other))
            vm.canGoBack.value shouldBe false
            vm.page.value shouldBe Page.AddSubscription
        }
    }

    test("удалить последнюю — подписок нет, страница подписки") {
        vmTest {
            val shelf = FakeShelf()
            val vm = vm(shelf = shelf)
            val port = FakePort()
            port.onDelete = { shelf.subs = Subscriptions.None }
            vm.attach(port)
            runCurrent()
            vm.changeSubscription()
            vm.confirmDelete(CARD)
            runCurrent()
            vm.subscriptions.value shouldBe Subscriptions.None
            vm.page.value shouldBe Page.AddSubscription
        }
    }

    test("переанализ — шторки нет, анализ сохранённой подписки по её id") {
        vmTest {
            val vm = vm(shelf = two())
            val port = FakePort()
            port.analysis = { kotlinx.coroutines.awaitCancellation() }
            vm.attach(port)
            runCurrent()
            vm.openCard(other)
            vm.reanalyze(other)
            runCurrent()
            vm.sheet.value shouldBe null
            port.reanalyzed shouldBe listOf("b")
            vm.page.value shouldBe Page.Scanning(null)
        }
    }
})
