package podbor.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import podbor.app.vpn.Acted
import podbor.app.vpn.SessionHost
import podbor.app.vpn.SessionState
import podbor.driver.EngineError
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Фейковая сессия: номер подъёма; туннель — запись open/close в журнал. */
private data class Live(val n: Int)

/**
 * Фикстура с правилом Android для startId: команды нумеруются как onStartCommand, а `stopSelf(startId)` останавливает
 * сервис, только если после этой команды новых не приходило. В журнал — «ended<id>» и, если правило сработало, «stopped».
 */
/** [gate] — подъём ждёт её: состояние между стартом и подъёмом видно без гонки с планировщиком. */
private class Fixture(private val fail: Boolean = false, private val gate: CompletableDeferred<Unit>? = null) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var ups = 0
    private val lastId = AtomicInteger(0)
    val host = SessionHost<Live>(
        scope = scope,
        bring = {
            gate?.await()
            val n = ++ups
            install({ events += "open$n" }, { _, _ -> events += "close$n" })
            if (fail) EngineError.Failed("runXray").left() else Live(n).right()
        },
        ended = { id ->
            events += "ended$id"
            if (id == lastId.get()) events += "stopped"
        },
    )

    fun start() = host.start(lastId.incrementAndGet())
    fun stop() = host.stop(lastId.incrementAndGet())

    /** Ждёт подъёма номер [n]: старт асинхронный, прежняя поднятая сессия ещё видна. */
    suspend fun up(n: Int = 1): Live = withTimeout(5_000) {
        (host.state.first { it is SessionState.Up && it.live.n == n } as SessionState.Up<Live>).live
    }

    suspend fun down() = withTimeout(5_000) { host.state.first { it == SessionState.Down } }
}

class SessionHostTest : FunSpec({

    test("стоп посреди медленного нажатия: нажатие отменено до release — ни памяти, ни экрана") {
        val f = Fixture()
        f.start()
        f.up()
        val started = CompletableDeferred<Unit>()
        val press = f.scope.async {
            f.host.act {
                started.complete(Unit)
                delay(10_000)
                f.events += "remember"
                f.events += "screen"
            }
        }
        started.await()
        f.stop()
        withTimeout(5_000) { press.await() } shouldBe Acted.NoSession
        f.down()
        // Down выставляется раньше ended(startId): журнал дописывается чуть позже — ждём его конца.
        withTimeout(5_000) { while ("stopped" !in f.events) delay(10) }
        f.events shouldBe listOf("open1", "close1", "ended2", "stopped")
        f.scope.cancel()
    }

    test("действие без сессии — NoSession, блок не исполняется") {
        val f = Fixture()
        f.host.act { f.events += "press" } shouldBe Acted.NoSession
        f.events shouldBe emptyList()
        f.scope.cancel()
    }

    test("действие на поднятой сессии — Done со значением") {
        val f = Fixture()
        f.start()
        f.up()
        f.host.act { it.n * 10 } shouldBe Acted.Done(10)
        f.scope.cancel()
    }

    test("выкл → вкл подряд: новый туннель только после закрытия старого; новая сессия остаётся поднятой") {
        val f = Fixture()
        f.start()
        f.up()
        f.stop()
        f.start()
        f.up(2).n shouldBe 2
        delay(200)
        f.host.state.value.shouldBeInstanceOf<SessionState.Up<Live>>()
        // Стоп, за которым уже стоит старт, сервис не завершает: stopSelf(2) после старта 3 — пустой (так было на телефоне).
        f.events shouldBe listOf("open1", "close1", "ended2", "open2")
        f.scope.cancel()
    }

    test("стоп без сессии (сервис создан самим стопом) всё равно завершает сервис") {
        val f = Fixture()
        f.stop()
        withTimeout(5_000) { while (f.events.size < 2) delay(10) }
        f.events shouldBe listOf("ended1", "stopped")
        f.scope.cancel()
    }

    test("повторный старт поднятой сессии ничего не делает") {
        val f = Fixture()
        f.start()
        f.up()
        f.start()
        delay(200)
        f.events shouldBe listOf("open1")
        f.scope.cancel()
    }

    test("не поднялась → Failed остаётся (не затирается Down), область освобождена, сервис завершён") {
        val f = Fixture(fail = true)
        f.start()
        withTimeout(5_000) { f.host.state.first { it is SessionState.Failed } } shouldBe SessionState.Failed(EngineError.Failed("runXray"))
        delay(100)
        f.host.state.value shouldBe SessionState.Failed(EngineError.Failed("runXray"))
        f.events shouldBe listOf("open1", "close1", "ended1", "stopped")
        f.scope.cancel()
    }

    test("старт пришёл во время неудачного подъёма → stopSelf с номером этого старта: сервис завершается, не висит") {
        val gate = CompletableDeferred<Unit>()
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = SessionHost<Live>(
            scope = scope,
            bring = { gate.await(); EngineError.Failed("runXray").left() },
            ended = { id -> events += "ended$id" },
        )
        host.start(1)
        withTimeout(5_000) { host.state.first { it == SessionState.Starting } }
        host.start(2)
        delay(100)
        gate.complete(Unit)
        withTimeout(5_000) { while (events.isEmpty()) delay(10) }
        delay(100)
        events shouldBe listOf("ended2")
        scope.cancel()
    }

    test("старт сразу ставит Starting: открытие, ждущее подъёма, не примет прежний Down за итог") {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(gate = gate)
        f.start()
        f.host.state.value shouldBe SessionState.Starting
        gate.complete(Unit)
        f.up()
        f.scope.cancel()
    }
})
