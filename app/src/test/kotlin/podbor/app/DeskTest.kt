package podbor.app

import arrow.core.nonEmptyListOf
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import podbor.app.sub.SubscriptionStore
import podbor.app.vpn.Desk
import podbor.app.vpn.ExitOrigin
import podbor.app.vpn.Live
import podbor.app.vpn.ScanPorts
import podbor.app.vpn.SessionHost
import podbor.app.vpn.SessionState
import podbor.core.WhiteLists
import podbor.core.Blame
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.Handshake
import podbor.core.LineObservation
import podbor.core.Memory
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.Reach
import podbor.core.Sample
import podbor.core.Scenario
import podbor.core.Server
import podbor.core.ServerId
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.Transport
import podbor.core.GENERAL_FOLLOWS
import podbor.core.Instagram
import podbor.core.Telegram
import podbor.core.Verdict
import podbor.core.board
import podbor.core.memoryKey
import podbor.core.rememberSuccess
import podbor.driver.Clock
import podbor.driver.ConfigOpts
import podbor.driver.Driver
import podbor.driver.EXIT_BALANCER
import podbor.driver.ExitsState
import podbor.driver.EndpointGate
import podbor.driver.InMemoryPassportStore
import podbor.driver.ExitProbe
import podbor.driver.classify
import podbor.driver.LineProbe
import podbor.driver.Store
import podbor.driver.XrayApi
import podbor.driver.XrayEngine
import podbor.driver.detectLine
import java.nio.file.Files

private val mts = OsNetwork.Cellular("MTS", "25001")

/** Раунд «сеть чистая»: эталоны в РФ и за границей отвечают. */
private val openLine = LineObservation(
    nonEmptyListOf(0, 1, 2).map { Sample("ru$it", Handshake.Ok(connectMs = 30)) },
    nonEmptyListOf(0, 1, 2).map { Sample("far$it", Handshake.Ok(connectMs = 30)) },
)

private fun outbound(i: Int): JsonObject = buildJsonObject {
    put("protocol", "vless"); put("tag", "Сервер $i")
    putJsonObject("settings") { put("address", "10.0.0.${i + 1}"); put("port", 443); put("id", "00000000-0000-0000-0000-000000000000") }
}

/**
 * Сервис без Android: настоящие SessionHost, Desk и Driver на фейковых портах. Подъём сессии ждёт [gate]; линия —
 * мгновенная; Телега отвечает на портах [fitsPorts], Инста — на [igFitsPorts]; первый setExit общего ждёт [setExitDelayMs].
 * Журнал [events] — линия, общий балансер и пробы: по нему видно, что линия не меряется заново и порядок снятия выхода.
 * Сцена живёт в [scope] теста (`backgroundScope`), часы — тестовые: один диспетчер и виртуальное время, порядок задают
 * шаги теста (`runCurrent`, `await`), а не планировщик.
 */
private class Rig(
    scope: CoroutineScope,
    nowMs: () -> Long,
    val memory: (List<Server>) -> Memory = { Memory.Empty },
    val fitsPorts: Set<Int> = emptySet(),
    val igFitsPorts: Set<Int> = emptySet(),
    val setExitDelayMs: Long = 0,
    /** Сколько отвечает проба восстановленного выхода (проверка при открытии) и проба при выбранном (нажатие). */
    val recalledProbeMs: Long = 0,
    val chosenProbeMs: Long = 0,
    val services: Set<Service> = setOf(Telegram),
    /** Вшитые списки белых для подсказок нажатию; null — списков нет. */
    val lists: WhiteLists? = null,
) {
    /** Сколько раз Desk спросил списки (подсказки нажатию считаются раз на прогретый Xray). */
    var listsCalls = 0
    val gate = CompletableDeferred<Unit>()
    val events = mutableListOf<String>()

    /** Пробы, отменённые посреди ответа: метка сервиса. */
    val cancelled = mutableListOf<String>()
    val setExitStarted = CompletableDeferred<Unit>()
    var saved: Memory? = null
    var saves = 0
    private val line = LineProbe { events += "line"; openLine }
    private val clock = Clock { nowMs() }
    var desk: Desk

    private fun driver(servers: () -> List<Server>): Driver = Driver(
        converter = { _ -> listOf(outbound(0), outbound(1)).right() },
        engine = XrayEngine { _ -> Unit.right() },
        api = object : XrayApi {
            override suspend fun routeOf(ip: String) = "s001".right()
            override suspend fun setExit(balancer: String, tag: String) = Unit.right().also {
                if (balancer != EXIT_BALANCER) return@also
                // Медленный — только первый вызов общего (восстановление); выходы нажатия — сразу.
                if (setExitStarted.complete(Unit)) delay(setExitDelayMs)
                events += "setExit $tag"
            }
            override suspend fun clearExit(balancer: String) = Unit.right().also {
                if (balancer == EXIT_BALANCER) events += "clearExit"
            }
        },
        prober = { spec, _, port ->
            val service = services.first { it.probe == spec }
            val origin = desk.origin.value[service.id] ?: ExitOrigin.Chosen
            // Телега — прежними метками, Инста — со своей.
            val who = if (service == Telegram) "" else "${service.label} "
            events += "probe $who$port ($origin)"
            try {
                delay(if (origin == ExitOrigin.Recalled) recalledProbeMs else chosenProbeMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += service.label
                throw e
            }
            if (service == Telegram) classify(if (port in fitsPorts) 3 else 0, Reach.Up) else (port in igFitsPorts).let { Verdict.Instagram(it, it) }
        },
        exitProbe = ExitProbe { null },
        gate = EndpointGate(),
        resolver = { host -> host },
        line = line,
        clock = clock,
        store = object : Store {
            override fun loadMemory() = memory(servers())
            override fun saveMemory(m: Memory) { saved = m; saves++ }
        },
        passportStore = InMemoryPassportStore(),
        tape = { },
        opts = ConfigOpts(assetDir = "/assets", services = services),
    )

    val host = SessionHost<Live>(
        scope = scope,
        bring = {
            gate.await()
            var warmServers: List<Server> = emptyList()
            val d = driver { warmServers }
            with(d) { warmUp("подписка") }.map { warm -> warmServers = warm.servers; Live(d, warm) }
        },
        ended = { },
    )

    init {
        desk = Desk(
            host, os = { mts }, detect = { os, onBoard -> detectLine(line, clock, { }, os, services, onBoard) }, services = services,
            xray = Mutex(),
            // Анализ здесь не зовётся (он — в ScanDeskTest): порты анализа — заглушки.
            scan = ScanPorts(
                download = { null }, servers = { 0 }, warm = { _ -> error("анализ не зовётся") },
                lists = { listsCalls++; lists },
                subscriptions = SubscriptionStore(Files.createTempDirectory("desk").toFile()), passports = InMemoryPassportStore(),
                stopSession = { },
            ),
        )
    }

    suspend fun up() = host.state.first { it is SessionState.Up }

    /** Выходы балансеров поднятой сессии; без сессии — пусто (новая сессия начинает без выходов). */
    fun exits(): ExitsState = (host.state.value as? SessionState.Up)?.live?.driver?.exits?.state?.value ?: ExitsState(null, emptyMap())

    fun status(service: ServiceId): CardStatus = desk.board.value.cards.single { it.service == service }.status
}

/** Память: «сеть чистая» у МТС — последним работал второй сервер (s002). */
private val rememberSecond: (List<Server>) -> Memory = { servers ->
    Memory.Empty.rememberSuccess(memoryKey(mts, Scenario.MobileOpen, Telegram.id), servers[1], 1L)
}

/** Память двух сервисов: Телега — s002, Инста — s001. */
private val rememberBoth: (List<Server>) -> Memory = { servers ->
    rememberSecond(servers).rememberSuccess(memoryKey(mts, Scenario.MobileOpen, Instagram.id), servers[0], 1L)
}

private val first = ExitRef("Сервер 0", Transport.Other("tcp"))
private val second = ExitRef("Сервер 1", Transport.Other("tcp"))

private fun TestScope.rig(
    memory: (List<Server>) -> Memory = { Memory.Empty },
    fitsPorts: Set<Int> = emptySet(),
    igFitsPorts: Set<Int> = emptySet(),
    setExitDelayMs: Long = 0,
    recalledProbeMs: Long = 0,
    chosenProbeMs: Long = 0,
    services: Set<Service> = setOf(Telegram),
    lists: WhiteLists? = null,
) = Rig(backgroundScope, { testScheduler.currentTime }, memory, fitsPorts, igFitsPorts, setExitDelayMs, recalledProbeMs, chosenProbeMs, services, lists)

/** Нажатие без диспетчеризации: до первой приостановки — синхронно, экран сразу после старта виден без гонки. */
private fun TestScope.pressNow(s: Rig) = async(start = CoroutineStart.UNDISPATCHED) { s.desk.press() }

class DeskTest : FunSpec({

    test("линия готова раньше подъёма сессии → плашка сети опубликована до Up; после Up линия не меряется заново") {
        runTest {
            val s = rig()
            s.host.start(1)
            val open = async { s.desk.open() }
            runCurrent()
            s.desk.board.value.network.shouldBeInstanceOf<NetworkCard.Measured>().scenario shouldBe Scenario.MobileOpen
            s.host.state.value shouldBe SessionState.Starting
            s.gate.complete(Unit)
            open.await() shouldBe true
            s.events.count { it == "line" } shouldBe 1
            s.status(Telegram.id) shouldBe CardStatus.Unknown
        }
    }

    test("восстановление: память есть → выход в Xray, проверка Телеги через него (слова «последний рабочий») → Works") {
        runTest {
            val s = rig(memory = rememberSecond, fitsPorts = setOf(20001))
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            open.await() shouldBe true
            s.exits().general shouldBe ServerId("s002")
            s.status(Telegram.id) shouldBe CardStatus.Works(second)
            s.desk.origin.value shouldBe emptyMap()
            s.saved shouldBe null
        }
    }

    test("восстановление: выход из памяти не отвечает → Dead с виной сервера; выход снят — Xray снова сам, общий выход пуст") {
        runTest {
            val s = rig(memory = rememberSecond)
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            open.await() shouldBe true
            s.status(Telegram.id) shouldBe CardStatus.Dead(second, Blame.Server)
            s.exits() shouldBe ExitsState(null, emptyMap())
            s.saved shouldBe null
        }
    }

    test("восстановленный выход Инсты мёртв на открытии → снят (Инста снова за общим); Телега работает и остаётся") {
        runTest {
            val s = rig(memory = rememberBoth, fitsPorts = setOf(20001), services = setOf(Telegram, Instagram))
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            open.await() shouldBe true
            s.status(Telegram.id) shouldBe CardStatus.Works(second)
            s.status(Instagram.id) shouldBe CardStatus.Dead(first, Blame.Server)
            s.exits() shouldBe ExitsState(ServerId("s002"), mapOf(Telegram.id to ServerId("s002")))
            s.desk.origin.value shouldBe emptyMap()
        }
    }

    test("восстановленный общий мёртв для Телеги, Инста за ним работает → Инста закреплена на нём до снятия общего") {
        // Инста памяти не имеет — шла за общим (s002) и прошла пробу на нём: по правилу такой выход закрепляется,
        // иначе снятие общего увело бы её балансер к выбору Xray, а строка осталась бы «IG ✓ Сервер 1».
        runTest {
            val s = rig(memory = rememberSecond, igFitsPorts = setOf(20001), services = setOf(Telegram, Instagram))
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            open.await() shouldBe true
            s.status(Telegram.id) shouldBe CardStatus.Dead(second, Blame.Server)
            s.status(Instagram.id) shouldBe CardStatus.Works(second)
            s.exits() shouldBe ExitsState(null, mapOf(Instagram.id to ServerId("s002")))
        }
    }

    test("origin — по сервису: выход Телеги выбран в сессии, Инсты — восстановлен из памяти") {
        runTest {
            val s = rig(
                memory = { servers -> Memory.Empty.rememberSuccess(memoryKey(mts, Scenario.MobileOpen, Instagram.id), servers[1], 1L) },
                fitsPorts = setOf(20000),
                igFitsPorts = setOf(20001),
                services = setOf(Telegram, Instagram),
            )
            s.gate.complete(Unit)
            s.host.start(1)
            val up = s.up()
            (up as SessionState.Up).live.driver.exits.make(GENERAL_FOLLOWS, ServerId("s001"))
            s.events.clear()
            s.desk.open() shouldBe true
            s.events.filter { it.startsWith("probe") }.toSet() shouldBe setOf("probe 20000 (Chosen)", "probe IG 20001 (Recalled)")
            s.status(Telegram.id) shouldBe CardStatus.Works(first)
            s.status(Instagram.id) shouldBe CardStatus.Works(second)
            s.exits().pinned shouldBe mapOf(Telegram.id to ServerId("s001"), Instagram.id to ServerId("s002"))
        }
    }

    test("сессия уже поднята, выхода нет (повтор открытия, поздняя привязка) → линия в сессии, восстановление и проба") {
        runTest {
            val s = rig(memory = rememberSecond, fitsPorts = setOf(20001))
            s.gate.complete(Unit)
            s.host.start(1)
            s.up()
            s.desk.open() shouldBe true
            s.desk.board.value.network.shouldBeInstanceOf<NetworkCard.Measured>()
            s.exits().general shouldBe ServerId("s002")
            s.status(Telegram.id) shouldBe CardStatus.Works(second)
        }
    }

    test("выход уже есть в сессии (возврат на экран) → память не читается, проверяется текущий") {
        runTest {
            val s = rig(memory = { error("память не нужна") }, fitsPorts = setOf(20000))
            s.gate.complete(Unit)
            s.host.start(1)
            val up = s.up()
            // Выход выставлен в этой сессии (прошлым нажатием): через выходы драйвера, как в бою.
            (up as SessionState.Up).live.driver.exits.make(GENERAL_FOLLOWS, ServerId("s001"))
            s.exits().general shouldBe ServerId("s001")
            s.desk.open() shouldBe true
            s.status(Telegram.id) shouldBe CardStatus.Works(first)
        }
    }

    test("стоп во время восстановления: восстановление отменено вместе с сессией — ни выхода, ни памяти, экран сброшен") {
        runTest {
            val s = rig(memory = rememberSecond, fitsPorts = setOf(20001), setExitDelayMs = 10_000)
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            s.setExitStarted.await()
            s.host.stop(2)
            open.await() shouldBe false
            runCurrent()
            s.host.state.value shouldBe SessionState.Down
            s.events.filter { it != "line" } shouldBe emptyList()
            s.saved shouldBe null
            s.desk.board.value shouldBe board(setOf(Telegram), emptyMap())
            s.desk.origin.value shouldBe emptyMap()
        }
    }

    test("нажатие во время замера открытия: сразу «ищу», замер отменён (восстановленный выход снят), линия не меряется заново") {
        runTest {
            val s = rig(memory = rememberSecond, fitsPorts = setOf(20000, 20001), recalledProbeMs = 10_000)
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            runCurrent()
            s.events shouldContain "probe 20001 (Recalled)"
            val press = pressNow(s)
            s.desk.board.value.button shouldBe Button.Searching
            press.await()
            // Открытие кончилось без сброса экрана: замер уступил нажатию, а не потерял сессию.
            open.await() shouldBe true
            s.events.count { it == "line" } shouldBe 1
            s.desk.board.value.button shouldBe Button.Green
            s.exits().general shouldNotBe null
            s.saves shouldBe 1
        }
    }

    test("нажатие во время открытия вытесняет открытия всех сервисов: обе пробы отменены, оба восстановленных выхода сняты") {
        runTest {
            val s = rig(
                memory = rememberBoth,
                fitsPorts = setOf(20000, 20001),
                igFitsPorts = setOf(20000, 20001),
                recalledProbeMs = 10_000,
                services = setOf(Telegram, Instagram),
            )
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            runCurrent()
            s.events.containsAll(listOf("probe 20001 (Recalled)", "probe IG 20000 (Recalled)")) shouldBe true
            val press = pressNow(s)
            // «Ищу» сразу — у обоих сервисов.
            s.desk.board.value.button shouldBe Button.Searching
            s.desk.board.value.cards.forEach { it.status shouldBe CardStatus.Searching }
            press.await()
            open.await() shouldBe true
            s.cancelled.toSet() shouldBe setOf("TG", "IG")
            // Восстановленный общий снят после отменённой пробы, до нажатия.
            s.events.indexOf("clearExit") shouldBeGreaterThan s.events.indexOf("probe 20001 (Recalled)")
            s.desk.board.value.button shouldBe Button.Green
            s.desk.board.value.cards.forEach { it.status.shouldBeInstanceOf<CardStatus.Found>() }
            s.desk.origin.value shouldBe emptyMap()
        }
    }

    test("нажатие посреди восстановления: Инста уже закреплена, Телега ещё нет — непроверенный выход Инсты снят") {
        runTest {
            val s = rig(memory = rememberBoth, fitsPorts = setOf(20000, 20001), setExitDelayMs = 10_000, services = setOf(Telegram, Instagram))
            s.host.start(1)
            val open = async { s.desk.open() }
            s.gate.complete(Unit)
            // Восстановление по ServiceId: svc.insta уже принят, общий выход Телеги висит.
            s.setExitStarted.await()
            s.exits().pinned[Instagram.id] shouldBe ServerId("s001")
            s.desk.press()
            open.await() shouldBe true
            // Инста в нажатии не нашлась — своего выхода у неё нет, балансер за общим.
            s.status(Instagram.id).shouldBeInstanceOf<CardStatus.NotFound>()
            s.exits().pinned.keys shouldBe setOf(Telegram.id)
        }
    }

    test("повторное нажатие, пока идёт подбор, в очередь не встаёт: подбор один") {
        runTest {
            val s = rig(chosenProbeMs = 300)
            s.gate.complete(Unit)
            s.host.start(1)
            s.up()
            s.desk.open() shouldBe true
            val first = pressNow(s)
            s.desk.board.value.button shouldBe Button.Searching
            // Первый подбор идёт: повторы возвращаются, не дожидаясь его (виртуальное время стоит).
            val before = testScheduler.currentTime
            s.desk.press()
            s.desk.press()
            testScheduler.currentTime shouldBe before
            first.await()
            s.saves shouldBe 1
        }
    }

    test("подсказки белых списков нажатию — раз на прогретый Xray: второе нажатие списки не пересчитывает") {
        runTest {
            val s = rig(lists = WhiteLists.of(listOf("10.0.0.1/32"), emptyList()))
            s.gate.complete(Unit)
            s.host.start(1)
            s.up()
            s.desk.open() shouldBe true
            s.desk.press()
            s.desk.press()
            s.listsCalls shouldBe 1
        }
    }
})
