package podbor.screen

import androidx.lifecycle.SavedStateHandle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.core.Board
import podbor.core.Button
import podbor.core.HARDCODED_SERVICES
import podbor.core.PairPulse
import podbor.core.Since
import podbor.core.PulseView
import podbor.core.ServiceId
import podbor.core.Tally
import podbor.core.Telegram
import podbor.core.Traffic
import podbor.core.board

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest : FunSpec({

    test("сервис подключился и сессия поднялась — VPN поднят; отвязался — выключен, доска остаётся последней") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.attach(port)
            port.session.value = SessionView.Starting
            runCurrent()
            port.session.value = SessionView.Up
            val pressed: Board = port.board.value.copy(button = Button.Searching)
            port.board.value = pressed
            runCurrent()
            linkOf(vm.facts.value) shouldBe Link.Up
            vm.board.value shouldBe pressed

            vm.detach()
            runCurrent()
            linkOf(vm.facts.value) shouldBe Link.Off
            vm.board.value shouldBe pressed
        }
    }

    test("пульс порта — на экране, пока экран смотрит; перестал смотреть — порт без подписчика (опрос редкий); без порта — пусто") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.attach(port)
            val watching = launch { vm.pulse.collect {} }
            val seen = PulseView(mapOf(Telegram.id to PairPulse.Seen(340_000, 12_000, Since.Ago(2_000))), Tally("wifi", Traffic(192_000, 32_200_000)), null)
            port.pulse.value = seen
            runCurrent()
            vm.pulse.value shouldBe seen
            port.pulse.subscriptionCount.value shouldBe 1

            watching.cancel()
            advanceTimeBy(PULSE_STOP_MS + 1)
            runCurrent()
            port.pulse.subscriptionCount.value shouldBe 0

            val again = launch { vm.pulse.collect {} }
            vm.detach()
            runCurrent()
            vm.pulse.value shouldBe PulseView.Empty
            again.cancel()
        }
    }

    test("старт выдан до привязки — «поднимаю VPN», дальше Starting и Up — поднят") {
        vmTest {
            val vm = vm()
            vm.issued()
            linkOf(vm.facts.value) shouldBe Link.Starting
            val port = FakePort()
            vm.attach(port)
            port.session.value = SessionView.Starting
            runCurrent()
            port.session.value = SessionView.Up
            runCurrent()
            linkOf(vm.facts.value) shouldBe Link.Up
        }
    }

    test("отказ в согласии — «отказано», пока не выдан новый старт") {
        vmTest {
            val vm = vm()
            vm.asked(Ask.VpnConsent)
            linkOf(vm.facts.value) shouldBe Link.Consent
            vm.answered()
            vm.refused()
            linkOf(vm.facts.value) shouldBe Link.Refused
            vm.issued()
            linkOf(vm.facts.value) shouldBe Link.Starting
        }
    }

    test("спросил человека — вопрос открыт, пока не ответил; ответ закрывает") {
        vmTest {
            val vm = vm()
            vm.ask.value shouldBe null
            vm.asked(Ask.Disclosure)
            vm.ask.value shouldBe Ask.Disclosure
            // Шторка → системный запрос: вопрос сменился, без «ничего не открыто» между ними.
            vm.asked(Ask.Permissions)
            vm.ask.value shouldBe Ask.Permissions
            vm.answered()
            vm.ask.value shouldBe null
        }
    }

    test("процесс убит посреди вопроса — новый ViewModel помнит, что открыто; отвеченный — не помнит") {
        vmTest {
            val saved = SavedStateHandle()
            vm(saved = saved).asked(Ask.Disclosure)
            vm(saved = saved).ask.value shouldBe Ask.Disclosure

            vm(saved = saved).answered()
            vm(saved = saved).ask.value shouldBe null
        }
    }

    test("процесс убит посреди согласия на VPN — после восстановления «жду согласия», а не «выключен»") {
        vmTest {
            val saved = SavedStateHandle()
            vm(saved = saved).asked(Ask.VpnConsent)
            linkOf(vm(saved = saved).facts.value) shouldBe Link.Consent
        }
    }

    test("отказ переживает смерть процесса; новый старт его снимает — и для следующего восстановления тоже") {
        vmTest {
            val saved = SavedStateHandle()
            vm(saved = saved).refused()
            val restored = vm(saved = saved)
            linkOf(restored.facts.value) shouldBe Link.Refused

            restored.issued()
            linkOf(vm(saved = saved).facts.value) shouldBe Link.Off
        }
    }

    test("сессия не поднялась и сервис ушёл — остаётся «не поднялся», не «выключен»") {
        vmTest {
            val vm = vm()
            val port = FakePort()
            vm.issued()
            vm.attach(port)
            port.session.value = SessionView.Starting
            runCurrent()
            port.session.value = SessionView.Failed("runXray")
            runCurrent()
            vm.detach()
            runCurrent()
            linkOf(vm.facts.value) shouldBe Link.Failed
        }
    }

    test("обновлялка: известный новее релиз — чип сразу, ещё до проверки") {
        vmTest {
            val vm = vm(FakeMemory(release = NEWER))
            watching(vm)
            runCurrent()
            vm.update.value shouldBe Offer("0.9.2", NEWER.url)
        }
    }

    test("обновлялка: экран виден — один запрос, ответ запомнен, чип по ответу") {
        vmTest {
            val memory = FakeMemory()
            val vm = vm(memory)
            val port = FakePort().apply { answer = { NEWER } }
            vm.attach(port)
            watching(vm)
            runCurrent()
            port.asked shouldBe 1
            memory.remembered shouldBe listOf(NEWER)
            vm.update.value shouldBe Offer("0.9.2", NEWER.url)
        }
    }

    test("обновлялка: каждое открытие экрана — новый запрос, без тормоза по времени") {
        vmTest {
            val vm = vm(FakeMemory(release = NEWER))
            val port = FakePort().apply { answer = { NEWER } }
            vm.attach(port)
            val first = watching(vm)
            runCurrent()
            first.cancel()
            // Запас WhileSubscribed: экран не вернулся за 5 с — поток остановлен; открыли снова — новый запрос.
            advanceTimeBy(5_001)
            runCurrent()
            watching(vm)
            runCurrent()
            port.asked shouldBe 2
        }
    }

    test("обновлялка: GitHub не ответил — память не тронута, спросим снова при смене сессии") {
        vmTest {
            val memory = FakeMemory()
            val vm = vm(memory)
            val port = FakePort()
            vm.attach(port)
            watching(vm)
            runCurrent()
            port.asked shouldBe 1
            memory.remembered shouldBe emptyList()
            // VPN поднялся — туннель для белых списков: ещё попытка.
            port.answer = { NEWER }
            port.session.value = SessionView.Up
            runCurrent()
            port.asked shouldBe 2
            vm.update.value shouldBe Offer("0.9.2", NEWER.url)
        }
    }

    test("обновлялка: экран не подписан — GitHub не спрашивается") {
        vmTest {
            val vm = vm()
            val port = FakePort().apply { answer = { NEWER } }
            vm.attach(port)
            runCurrent()
            port.asked shouldBe 0
        }
    }

    test("обновлялка: сборка не с GitHub — чипа нет и GitHub не спрашивается") {
        vmTest {
            val vm = vm(FakeMemory(release = NEWER), selfDistributed = false)
            val port = FakePort().apply { answer = { NEWER } }
            vm.attach(port)
            watching(vm)
            runCurrent()
            vm.update.value shouldBe null
            port.asked shouldBe 0
        }
    }

    test("обновлялка: экран ушёл посреди запроса — запрос отменён, ответ не запомнен") {
        vmTest {
            val memory = FakeMemory()
            val vm = vm(memory)
            val hang = CompletableDeferred<Release?>()
            val port = FakePort().apply { answer = { hang.await() } }
            vm.attach(port)
            val screen = watching(vm)
            runCurrent()
            port.asked shouldBe 1
            screen.cancel()
            // Запас WhileSubscribed: экран не вернулся за 5 с — поток остановлен.
            advanceTimeBy(5_001)
            runCurrent()
            hang.complete(NEWER)
            runCurrent()
            memory.remembered shouldBe emptyList()
        }
    }
})
