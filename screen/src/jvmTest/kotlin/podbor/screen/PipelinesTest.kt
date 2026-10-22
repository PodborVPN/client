package podbor.screen

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent

private val LINK = SubscriptionInput.Link("https://example.org/sub")

/** Конвейеры экрана: открытие сессии, плашка без VPN, удержание сервиса, анализ — порядок событий без флагов. */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelinesTest : FunSpec({

    test("открытие раньше сервиса — замер, как только сервис подключился; удался — ждать больше нечего") {
        vmTest {
            val vm = vm()
            vm.opened(askPermissions = false)
            runCurrent()
            vm.want.value.shouldBeInstanceOf<Want.Open>()
            val port = FakePort()
            vm.attach(port)
            runCurrent()
            port.opens shouldBe 1
            port.refreshes shouldBe 1
            vm.want.value shouldBe Want.Idle
        }
    }

    test("замера не было (сессию остановили посреди) — повтор на следующем подъёме, не раньше") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            port.measured = { false }
            vm.attach(port)
            vm.opened(askPermissions = false)
            runCurrent()
            port.opens shouldBe 1
            port.session.value = SessionView.Starting
            runCurrent()
            port.opens shouldBe 1
            port.measured = { true }
            port.session.value = SessionView.Up
            runCurrent()
            port.opens shouldBe 2
            vm.want.value shouldBe Want.Idle
        }
    }

    test("новое открытие посреди замера — замер заново для него; ждать после него нечего") {
        vmTest {
            var t = 1L
            val vm = vm(clock = { t })
            val port = FakePort()
            val first = CompletableDeferred<Boolean>()
            port.measured = { first.await() }
            vm.attach(port)
            vm.opened(askPermissions = false)
            runCurrent()
            port.measured = { true }
            t = 2
            vm.opened(askPermissions = false)
            runCurrent()
            port.opens shouldBe 2
            vm.want.value shouldBe Want.Idle
        }
    }

    test("главный: старт VPN нужен, когда вопросов нет; шторка разрешений — сначала она") {
        vmTest {
            val vm = vm()
            vm.opened(askPermissions = true)
            runCurrent()
            vm.ask.value shouldBe Ask.Disclosure
            vm.needsStart.value shouldBe false
            vm.answered()
            runCurrent()
            vm.needsStart.value shouldBe true
            vm.issued()
            runCurrent()
            vm.needsStart.value shouldBe false
        }
    }

    test("согласие спрошено и отказано — старт для этого открытия больше не нужен, сервис не держим; новое открытие — снова") {
        vmTest {
            val vm = vm()
            vm.opened(askPermissions = false)
            runCurrent()
            vm.needsStart.value shouldBe true
            vm.hold.value shouldBe true
            vm.asked(Ask.VpnConsent)
            vm.answered()
            vm.refused()
            runCurrent()
            vm.needsStart.value shouldBe false
            vm.hold.value shouldBe false
        }
    }

    test("страница подписки: плашка сети без VPN — раз на открытие, после ответа на вопрос; сервис держим") {
        vmTest {
            val vm = vm(shelf = FakeShelf(note = null))
            val port = FakePort()
            vm.attach(port)
            vm.opened(askPermissions = true)
            runCurrent()
            vm.page.value shouldBe Page.AddSubscription
            vm.hold.value shouldBe true
            vm.needsStart.value shouldBe false
            port.lines shouldBe 0
            vm.answered()
            runCurrent()
            port.lines shouldBe 1
            vm.want.value shouldBe Want.Idle
            port.opens shouldBe 0
        }
    }

    test("анализ: доски по ходу, второй вход посреди не перебивает, итог — доска; сервис держим") {
        vmTest {
            val vm = vm(shelf = FakeShelf(note = null))
            val port = FakePort()
            val done = CompletableDeferred<Unit>()
            port.analysis = { onBoard ->
                onBoard(SCAN.copy(finished = false))
                done.await()
                Either.Right(SCAN)
            }
            vm.attach(port)
            vm.input(Either.Right(LINK))
            runCurrent()
            vm.scan.value shouldBe ScanPhase.Running(SCAN.copy(finished = false))
            vm.hold.value shouldBe true
            vm.input(Either.Left(InputError.Empty))
            vm.input(Either.Right(SubscriptionInput.Text("vless")))
            runCurrent()
            port.analyses shouldBe 1
            vm.scan.value.shouldBeInstanceOf<ScanPhase.Running>()
            done.complete(Unit)
            runCurrent()
            vm.scan.value shouldBe ScanPhase.Done(SCAN)
        }
    }

    test("«ОК» посреди анализа — анализ прерван, подписка перечитана, главный и открытие; второе «ОК» — ничего") {
        vmTest {
            val shelf = FakeShelf(note = null)
            val vm = vm(shelf = shelf)
            val port = FakePort()
            port.analysis = { awaitCancellation() }
            vm.attach(port)
            vm.input(Either.Right(LINK))
            runCurrent()
            // Драйвер сохранил частичный паспорт.
            shelf.note = NOTE
            vm.finishScan()
            runCurrent()
            vm.scan.value shouldBe ScanPhase.Idle
            vm.page.value shouldBe Page.Main
            vm.subscriptionNote.value shouldBe NOTE
            port.opens shouldBe 1
            vm.finishScan()
            runCurrent()
            port.opens shouldBe 1
        }
    }

    test("тот же текст подписки после итога — анализ заново") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.attach(port)
            vm.input(Either.Right(LINK))
            runCurrent()
            vm.scan.value shouldBe ScanPhase.Done(SCAN)
            vm.input(Either.Right(LINK))
            runCurrent()
            port.analyses shouldBe 2
        }
    }

    test("вход не принялся — словами на странице подписки, без анализа") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.attach(port)
            vm.input(Either.Left(InputError.Unreadable))
            runCurrent()
            vm.scan.value shouldBe ScanPhase.Failed(InputError.Unreadable)
            vm.page.value shouldBe Page.AddSubscription
            port.analyses shouldBe 0
        }
    }

    test("«назад» после неудачной попытки при сохранённой подписке — главный и открытие (попытка могла погасить VPN)") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.attach(port)
            runCurrent()
            vm.input(Either.Left(InputError.NoServers))
            runCurrent()
            vm.canGoBack.value shouldBe true
            vm.back()
            runCurrent()
            vm.page.value shouldBe Page.Main
            port.opens shouldBe 1
        }
    }

    test("«Сменить подписку» и «назад» без попытки — главный, без нового открытия") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.attach(port)
            runCurrent()
            vm.changeSubscription()
            runCurrent()
            vm.page.value shouldBe Page.AddSubscription
            vm.back()
            runCurrent()
            vm.page.value shouldBe Page.Main
            port.opens shouldBe 0
        }
    }
})
