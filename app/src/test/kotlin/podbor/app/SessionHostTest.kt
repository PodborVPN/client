package podbor.app

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.app.vpn.Acted
import podbor.app.vpn.SessionHost
import podbor.app.vpn.SessionState
import podbor.app.vpn.view
import podbor.driver.EngineError
import podbor.screen.SessionView

/** Фейковая сессия: номер подъёма; туннель — запись open/close в журнал. */
private data class Live(val n: Int)

/**
 * Фикстура с правилом Android для startId: команды нумеруются как onStartCommand, а `stopSelf(startId)` останавливает
 * сервис, только если после этой команды новых не приходило. В журнал — «ended<id>» и, если правило сработало, «stopped».
 * Хост живёт в [scope] теста (`backgroundScope`): один диспетчер и виртуальное время — порядок задают шаги теста
 * (`runCurrent`), а не планировщик. `advanceUntilIdle` фоновых задач не ждёт — здесь не годится.
 */
private class Fixture(scope: CoroutineScope, private val fail: Boolean = false) {
    val events = mutableListOf<String>()
    var ups = 0
    private var lastId = 0
    val host = SessionHost<Live>(
        scope = scope,
        bring = {
            val n = ++ups
            install({ events += "open$n" }, { _, _ -> events += "close$n" })
            if (fail) EngineError.Failed("runXray").left() else Live(n).right()
        },
        ended = { id ->
            events += "ended$id"
            if (id == lastId) events += "stopped"
        },
    )

    fun start() = host.start(++lastId)
    fun stop() = host.stop(++lastId)
}

private fun TestScope.fixture(fail: Boolean = false) = Fixture(backgroundScope, fail)

class SessionHostTest : FunSpec({

    test("стоп посреди медленного нажатия: нажатие отменено до release — ни памяти, ни экрана") {
        runTest {
            val f = fixture()
            f.start()
            runCurrent()
            val press = async {
                f.host.act {
                    delay(10_000)
                    f.events += "remember"
                    f.events += "screen"
                }
            }
            runCurrent()
            f.stop()
            press.await() shouldBe Acted.NoSession
            runCurrent()
            f.host.state.value shouldBe SessionState.Down
            f.events shouldBe listOf("open1", "close1", "ended2", "stopped")
        }
    }

    test("действие без сессии — NoSession, блок не исполняется") {
        runTest {
            val f = fixture()
            f.host.act { f.events += "press" } shouldBe Acted.NoSession
            f.events shouldBe emptyList()
        }
    }

    test("действие на поднятой сессии — Done со значением") {
        runTest {
            val f = fixture()
            f.start()
            runCurrent()
            f.host.act { it.n * 10 } shouldBe Acted.Done(10)
        }
    }

    test("выкл → вкл подряд: новый туннель только после закрытия старого; новая сессия остаётся поднятой") {
        runTest {
            val f = fixture()
            f.start()
            runCurrent()
            // Оба номера выданы до исполнения стопа — как onStartCommand у Android.
            f.stop()
            f.start()
            runCurrent()
            (f.host.state.value as SessionState.Up).live shouldBe Live(2)
            // Стоп, за которым уже стоит старт, сервис не завершает: stopSelf(2) после старта 3 — пустой (так было на телефоне).
            f.events shouldBe listOf("open1", "close1", "ended2", "open2")
        }
    }

    test("стоп без сессии (сервис создан самим стопом) всё равно завершает сервис") {
        runTest {
            val f = fixture()
            f.stop()
            runCurrent()
            f.events shouldBe listOf("ended1", "stopped")
        }
    }

    test("повторный старт поднятой сессии ничего не делает") {
        runTest {
            val f = fixture()
            f.start()
            runCurrent()
            f.start()
            runCurrent()
            f.events shouldBe listOf("open1")
        }
    }

    test("не поднялась → Failed остаётся (не затирается Down), область освобождена, сервис завершён") {
        runTest {
            val f = fixture(fail = true)
            f.start()
            runCurrent()
            f.host.state.value shouldBe SessionState.Failed(EngineError.Failed("runXray"))
            f.events shouldBe listOf("open1", "close1", "ended1", "stopped")
        }
    }

    test("старт пришёл во время неудачного подъёма → stopSelf с номером этого старта: сервис завершается, не висит") {
        runTest {
            val engine = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val host = SessionHost<Live>(
                scope = backgroundScope,
                bring = { engine.await(); EngineError.Failed("runXray").left() },
                ended = { id -> events += "ended$id" },
            )
            host.start(1)
            runCurrent()
            host.state.value shouldBe SessionState.Starting
            host.start(2)
            runCurrent()
            engine.complete(Unit)
            runCurrent()
            events shouldBe listOf("ended2")
        }
    }

    test("старт сразу ставит Starting: открытие, ждущее подъёма, не примет прежний Down за итог") {
        runTest {
            val f = fixture()
            f.start()
            f.host.state.value shouldBe SessionState.Starting
            runCurrent()
            (f.host.state.value as SessionState.Up).live shouldBe Live(1)
        }
    }

    test("снимок для экрана: без ресурсов сессии, неудача — с причиной") {
        SessionState.Down.view() shouldBe SessionView.Down
        SessionState.Starting.view() shouldBe SessionView.Starting
        SessionState.Up(Live(1), CoroutineScope(Dispatchers.Unconfined)).view() shouldBe SessionView.Up
        SessionState.Failed(EngineError.Failed("runXray")).view() shouldBe SessionView.Failed("runXray")
    }

    test("снимок для экрана — вместе с состоянием: сервис, гаснущий на неудаче, уже видит Failed") {
        runTest {
            var seenAtEnd: SessionView? = null
            lateinit var host: SessionHost<Live>
            host = SessionHost(
                scope = backgroundScope,
                bring = { EngineError.Failed("runXray").left() },
                // Здесь Android зовёт stopSelf → onDestroy гасит область сервиса: снимок должен быть готов до этого.
                ended = { seenAtEnd = host.view.value },
            )
            host.start(1)
            host.view.value shouldBe SessionView.Starting
            runCurrent()
            seenAtEnd shouldBe SessionView.Failed("runXray")
        }
    }
})
