package podbor.screen

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
import podbor.core.ServiceId
import podbor.core.board

/** Сервис глазами экрана: значения — потоками, релиз — по запросу (счётчик вызовов, ответ подменяется). */
private class FakePort : ServicePort {
    override val session = MutableStateFlow<SessionView>(SessionView.Down)
    override val board = MutableStateFlow(board(HARDCODED_SERVICES, emptyMap()))
    override val exitOrigin = MutableStateFlow<Map<ServiceId, ExitOrigin>>(emptyMap())
    var asked = 0
    var answer: suspend () -> Release? = { null }

    override suspend fun latestRelease(): Release? {
        asked++
        return answer()
    }
}

private class FakeMemory(var at: Long? = null, var release: Release? = null) : UpdateMemory {
    val remembered = mutableListOf<Pair<Long, Release>>()
    override fun checkedAtMs(): Long? = at
    override fun known(): Release? = release
    override fun remember(atMs: Long, release: Release) {
        remembered += atMs to release
        at = atMs
        this.release = release
    }
}

private const val HOUR = 3_600_000L
private val NEWER = Release("v0.9.2", "https://example.org/podbor-0.9.2.apk")

/** ViewModel на часах теста: его область — фоновая область теста, гаснет с концом теста. */
private fun vmTest(block: suspend TestScope.() -> Unit) = runTest { block() }

private fun TestScope.vm(
    memory: UpdateMemory = FakeMemory(),
    selfDistributed: Boolean = true,
    now: Long = 10 * HOUR,
) = MainViewModel("0.9.1", selfDistributed, memory, { now }, backgroundScope)

/** Пока экран подписан на чип — значение живо (как collectAsStateWithLifecycle). */
private fun TestScope.watching(vm: MainViewModel): Job = backgroundScope.launch { vm.update.collect {} }

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
            vm.consentAsked()
            linkOf(vm.facts.value) shouldBe Link.Consent
            vm.refused()
            linkOf(vm.facts.value) shouldBe Link.Refused
            vm.issued()
            linkOf(vm.facts.value) shouldBe Link.Starting
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
            val vm = vm(FakeMemory(at = 10 * HOUR, release = NEWER))
            watching(vm)
            runCurrent()
            vm.update.value shouldBe Offer("0.9.2", NEWER.url)
        }
    }

    test("обновлялка: экран виден и пора — один запрос, ответ запомнен с временем, чип по ответу") {
        vmTest {
            val memory = FakeMemory()
            val vm = vm(memory)
            val port = FakePort().apply { answer = { NEWER } }
            vm.attach(port)
            watching(vm)
            runCurrent()
            port.asked shouldBe 1
            memory.remembered shouldBe listOf(10 * HOUR to NEWER)
            vm.update.value shouldBe Offer("0.9.2", NEWER.url)
        }
    }

    test("обновлялка: спрашивали меньше часа назад — GitHub не спрашивается") {
        vmTest {
            val vm = vm(FakeMemory(at = 10 * HOUR - 1))
            val port = FakePort().apply { answer = { NEWER } }
            vm.attach(port)
            watching(vm)
            runCurrent()
            port.asked shouldBe 0
        }
    }

    test("обновлялка: GitHub не ответил — время не ставится, спросим снова при смене сессии") {
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
