package podbor.driver

import arrow.core.Either
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right
import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import podbor.core.Blame
import podbor.core.Board
import podbor.core.CardStatus
import podbor.core.GENERAL_FOLLOWS
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.Button
import podbor.core.ExitRef
import podbor.core.Letter
import podbor.core.Endpoint
import podbor.core.LineObservation
import podbor.core.Memory
import podbor.core.Passport
import podbor.core.block
import podbor.core.Telegram
import podbor.core.memoryKey
import podbor.core.rememberSuccess
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.Prior
import podbor.core.Reach
import podbor.core.Scenario
import podbor.core.ServerId

/**
 * Мир на фейках: всё, что край зовёт, записывается. Проба по умолчанию — Телега: годен порт из [fitsPorts];
 * [prober] заменяет её целиком (трубопроводы с другой пробой). Хранилище помнит последнее сохранённое.
 */
internal class World(
    val ips: List<String>,
    val ruIps: Set<String>,
    val line: LineObservation,
    val fitsPorts: Set<Int>,
    val probeDelayMs: (Int) -> Long = { 0 },
    val engineFails: Boolean = false,
    val memory: Memory = Memory.Empty,
    val setExitDelayMs: Long = 0,
    val prober: Prober? = null,
    val gate: EndpointGate = EndpointGate(),
    val clock: Clock = Clock { System.currentTimeMillis() },
    val lineDelayMs: Long = 0,
    val saveDelayMs: Long = 0,
    val pressTimeoutMs: Long = 60_000,
    val services: Set<Service> = setOf(Telegram),
    val countries: Map<String, String> = emptyMap(),
    /** Выход по эталону: по SOCKS-порту кандидата; по умолчанию эталон молчит. */
    val exitProbe: ExitProbe = ExitProbe { null },
    /** Паспорт, сохранённый прошлым анализом. */
    val passport: Passport? = null,
    val scanTimeoutMs: Long = 150_000,
) {
    val probed = mutableListOf<Int>()
    val reached = mutableListOf<Endpoint>()
    /** Цели общего балансера (снятие — "clear"); [overrides] — все балансеры как «балансер=цель». */
    val exits = mutableListOf<String>()
    val overrides = mutableListOf<String>()
    val tape = mutableListOf<String>()
    @Volatile var saved: Memory? = null
    @Volatile var savedPassport: Passport? = null
    var lineCalls = 0
    var engineRuns = 0

    val lineProbe = LineProbe { synchronized(this) { lineCalls++ }; delay(lineDelayMs); line }

    fun outbound(i: Int, ip: String): JsonObject = buildJsonObject {
        put("protocol", "vless"); put("tag", "Сервер $i")
        putJsonObject("settings") { put("address", ip); put("port", 443); put("id", "00000000-0000-0000-0000-000000000000") }
    }

    private val telegram = Prober { _, server, port ->
        synchronized(probed) { probed += port }
        synchronized(reached) { reached += server.endpoint }
        delay(probeDelayMs(port))
        classify(if (port in fitsPorts) 3 else 0, Reach.Up)
    }

    fun driver() = Driver(
        converter = { _ -> ips.mapIndexed { i, ip -> outbound(i, ip) }.right() },
        engine = XrayEngine { _ -> engineRuns++; if (engineFails) EngineError.Failed("xray не стартовал").left() else Unit.right() },
        api = object : XrayApi {
            override suspend fun routeOf(ip: String) = (if (ip in ruIps) DIRECT else "s001").right()
            override suspend fun setExit(balancer: String, tag: String) = Unit.right().also {
                synchronized(exits) { overrides += "$balancer=$tag" }
                if (balancer == EXIT_BALANCER) {
                    delay(setExitDelayMs)
                    synchronized(exits) { exits += tag }
                }
            }
            override suspend fun clearExit(balancer: String) = Unit.right().also {
                synchronized(exits) {
                    overrides += "$balancer="
                    if (balancer == EXIT_BALANCER) exits += "clear"
                }
            }
        },
        prober = prober ?: telegram,
        exitProbe = exitProbe,
        gate = gate,
        resolver = { host -> host },
        line = lineProbe,
        clock = clock,
        store = object : Store {
            override fun loadMemory() = saved ?: memory
            override fun saveMemory(m: Memory) { Thread.sleep(saveDelayMs); saved = m }
        },
        passportStore = object : PassportStore {
            override fun load() = savedPassport ?: passport
            override fun save(p: Passport) { savedPassport = p }
        },
        tape = { l -> synchronized(tape) { tape += l } },
        opts = ConfigOpts(assetDir = "/assets", services = services),
        countryOf = { countries[it] },
        pressTimeoutMs = pressTimeoutMs,
        scanTimeoutMs = scanTimeoutMs,
    )
}

/** Карточка сервиса [id] сводного экрана. */
internal fun Board.card(id: ServiceId = Telegram.id): CardStatus = cards.single { it.service == id }.status

/** Выход, на котором «сидит» общий балансер до нажатия: как будто его выставило прошлое нажатие. */
private suspend fun Driver.sitOn(exit: ServerId?, w: World) {
    exit?.let { exits.make(GENERAL_FOLLOWS, it) }
    synchronized(w.exits) { w.exits.clear(); w.overrides.clear() }
}

internal fun World.pressNow(subscription: String, os: OsNetwork, currentExit: ServerId?, prior: Prior = Prior.None) =
    runBlocking {
        resourceScope {
            val d = driver()
            with(d) { warmUp(subscription) }.map { d.sitOn(currentExit, this@pressNow); d.press(it, services, os, prior) }
        }
    }

internal fun World.openNow(subscription: String, os: OsNetwork, currentExit: ServerId?) =
    runBlocking {
        resourceScope {
            val d = driver()
            with(d) { warmUp(subscription) }.map { d.sitOn(currentExit, this@openNow); d.open(it, services, os) }
        }
    }

class DriverTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)

    test("белые списки с фейками: зелёная, setExit с найденным тегом, пробы только Ru — своим SOCKS-портом и TCP к своему адресу") {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), ruIps = setOf("10.0.0.1", "10.0.0.2"), line = whitelist, fitsPorts = setOf(20001))
        val screen = w.pressNow("подписка", mts, currentExit = null).shouldBeInstanceOf<Either.Right<Board>>().value
        screen.button shouldBe Button.Green
        w.exits shouldContainExactly listOf("s002")
        w.probed shouldContain 20001
        w.reached shouldContain Endpoint("10.0.0.2", 443)
        (20002 in w.probed) shouldBe false // s003 — зарубежный вход, при белых списках не пробуется
        w.saved!!.successes.values.single().single().serverKey.isNotEmpty() shouldBe true
    }

    test("движок не стартовал → Left(EngineError): человек видит ошибку движка, а не «у провайдера ничего не работает»") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet(), engineFails = true)
        w.pressNow("подписка", mts, currentExit = null).shouldBeInstanceOf<Either.Left<EngineError>>()
        w.probed shouldBe emptyList()
    }

    test("ответ после Done не исполняется: выход один, нажатие не ждёт медленных проб") {
        val w = World(
            ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1", "10.0.0.2"), line = whitelist,
            fitsPorts = setOf(20000, 20001), probeDelayMs = { if (it == 20001) 2_000 else 0 },
        )
        val started = System.currentTimeMillis()
        w.pressNow("подписка", mts, currentExit = null)
        (System.currentTimeMillis() - started) shouldBeLessThan 1_500L
        w.exits shouldContainExactly listOf("s001")
    }

    test("красная кнопка: все Ru молчат → Red, «не найдено», setExit не зовётся") {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1", "10.0.0.2"), line = whitelist, fitsPorts = emptySet())
        val screen = w.pressNow("подписка", mts, currentExit = null).shouldBeInstanceOf<Either.Right<Board>>().value
        screen.button shouldBe Button.Red
        screen.card().shouldBeInstanceOf<CardStatus.NotFound>()
        w.exits shouldBe emptyList()
    }

    // Приватность Ленты — при отдаче наружу: буквы пишутся целиком, адреса и имена серверов на Ленте есть.
    test("Лента нажатия: шапка Run(Press), затем буквы целиком — Pressed с адресами и именами серверов") {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = setOf(20000))
        w.pressNow("подписка", mts, currentExit = ServerId("s002"))
        val rows = w.tape.map(::rowOf)
        rows.first().shouldBeInstanceOf<Row.Run>().head shouldBe Head.Press(Telegram.id)
        val pressed = rows.filterIsInstance<Row.Step>().first().letter.shouldBeInstanceOf<Letter.Pressed>()
        pressed.servers.map { it.endpoint.host } shouldBe listOf("10.0.0.1", "10.0.0.2")
        pressed.servers.map { it.name } shouldBe listOf("Сервер 0", "Сервер 1")
        pressed.currentExit shouldBe ServerId("s002")
    }

    test("открыл: белые списки, текущий зарубежный выход мёртв → виновата сеть; выход не трогаем, память не пишем") {
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = emptySet())
        val opening = w.openNow("подписка", mts, currentExit = ServerId("s002"))
            .shouldBeInstanceOf<Either.Right<Opening>>().value
        opening.board.button shouldBe Button.Ready
        opening.board.card() shouldBe CardStatus.Dead(ExitRef("Сервер 1", podbor.core.Transport.Other("tcp")), Blame.Whitelist)
        opening.prior.shouldBeInstanceOf<Prior.Detected>().rounds.last().line shouldBe whitelist
        w.probed shouldBe listOf(20001, 20001) // TCP есть, Телега молчит — неоднозначно, повтор один раз
        w.exits shouldBe emptyList()
        w.saved shouldBe null
    }

    test("открыл без выхода: только линия, внизу «выход не выбран»") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val opening = w.openNow("подписка", mts, currentExit = null)
            .shouldBeInstanceOf<Either.Right<Opening>>().value
        opening.board.card() shouldBe CardStatus.Unknown
        w.probed shouldBe emptyList()
        w.lineCalls shouldBe 2 // раунд «похоже на белые списки» — подозрение, сразу второй раунд для подтверждения
    }

    test("открыл с выходом не из подписки: Unlisted, проба выходу не запускается, на Ленте только тег") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = emptySet())
        val opening = w.openNow("подписка", mts, currentExit = ServerId("s999"))
            .shouldBeInstanceOf<Either.Right<Opening>>().value
        opening.board.card() shouldBe CardStatus.Unlisted(ServerId("s999"))
        w.probed shouldBe emptyList()
        w.tape.map(::rowOf).filterIsInstance<Row.Step>().first().letter
            .shouldBeInstanceOf<Letter.Opened>().vpn shouldBe podbor.core.Vpn.Unlisted(ServerId("s999"))
    }

    test("open и press на одном Warm: движок стартует раз; открытие подтвердило белые списки — нажатие линию не меряет") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = setOf(20000))
        val screen = runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                val opening = d.open(warm, w.services, mts)
                w.lineCalls shouldBe 2
                d.press(warm, w.services, mts, prior = opening.prior)
            }
        }
        w.lineCalls shouldBe 2
        w.engineRuns shouldBe 1
        screen.button shouldBe Button.Green
        screen.network.shouldBeInstanceOf<NetworkCard.Measured>().scenario shouldBe Scenario.MobileWhitelist
    }

    test("Лента открытия: шапка Run(Open) без раундов, затем Opened с выходом целиком") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        w.openNow("подписка", mts, currentExit = ServerId("s001"))
        val rows = w.tape.map(::rowOf)
        rows.first().shouldBeInstanceOf<Row.Run>().head shouldBe Head.Open(emptyList())
        val vpn = rows.filterIsInstance<Row.Step>().first().letter.shouldBeInstanceOf<Letter.Opened>().vpn
        vpn.shouldBeInstanceOf<podbor.core.Vpn.On>().exit.endpoint.host shouldBe "10.0.0.1"
    }

    test("промежуточные экраны нажатия: сначала Searching с «измеряю сеть», последний — тот же, что итог") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = setOf("10.0.0.1"), line = whitelist, fitsPorts = setOf(20000))
        val seen = mutableListOf<Board>()
        val screen = runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                d.press(warm, w.services, mts, onBoard = { seen += it })
            }
        }
        seen.first().button shouldBe Button.Searching
        seen.first().network shouldBe NetworkCard.Measuring(mts)
        seen.last() shouldBe screen
        // Подозрение после первого раунда видно словами, пока второй раунд в пути.
        seen.any { (it.network as? NetworkCard.Measured)?.evidence?.endsWith("проверяю ещё раз") == true } shouldBe true
    }

    test("промежуточные экраны открытия: сначала «измеряю сеть», последний — экран открытия") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val seen = mutableListOf<Board>()
        val opening = runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                d.open(warm, w.services, mts, onBoard = { seen += it })
            }
        }
        seen.first().network shouldBe NetworkCard.Measuring(mts)
        seen.last() shouldBe opening.board
    }

    test("линия до Xray: detectLine меряет только линию — плашка, раунды для открытия; движок не нужен") {
        val w = World(ips = emptyList(), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val seen = mutableListOf<Board>()
        val opening = runBlocking { detectLine(w.lineProbe, Clock { System.currentTimeMillis() }, { l -> w.tape += l }, mts, w.services) { seen += it } }
        opening.board.network.shouldBeInstanceOf<NetworkCard.Measured>().scenario shouldBe Scenario.MobileWhitelist
        opening.prior.shouldBeInstanceOf<Prior.Detected>().rounds.size shouldBe 2
        seen.first().network shouldBe NetworkCard.Measuring(mts)
        w.lineCalls shouldBe 2
        w.engineRuns shouldBe 0
        w.probed shouldBe emptyList()
    }

    test("открытие со свежими раундами линии: линию не меряет, первый же экран — с плашкой; проба выхода идёт") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = setOf(20000))
        val known = Letter.LineObserved(System.currentTimeMillis(), line(Reach.Up, Reach.Up))
        val seen = mutableListOf<Board>()
        val opening = runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                d.sitOn(ServerId("s001"), w)
                d.open(warm, w.services, mts, prior = Prior.Detected(nonEmptyListOf(known))) { seen += it }
            }
        }
        w.lineCalls shouldBe 0
        seen.first().network.shouldBeInstanceOf<NetworkCard.Measured>().scenario shouldBe Scenario.MobileOpen
        opening.board.card() shouldBe CardStatus.Works(ExitRef("Сервер 0", podbor.core.Transport.Other("tcp")))
        // Раунды линии переходят дальше — нажатию.
        opening.prior.shouldBeInstanceOf<Prior.Detected>().rounds.single() shouldBe known
        w.probed shouldBe listOf(20000)
        // Раунды до Xray — в шапке прогона, а не нотой со ссылкой.
        val rows = w.tape.map(::rowOf)
        rows.first().shouldBeInstanceOf<Row.Run>().head shouldBe Head.Open(listOf(known))
        rows.filterIsInstance<Row.Note>() shouldBe emptyList()
    }

    test("открытие с устаревшими раундами (старше LINE_FRESH_MS) меряет линию само") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = emptySet())
        val stale = Letter.LineObserved(System.currentTimeMillis() - podbor.core.LINE_FRESH_MS - 1_000, line(Reach.Up, Reach.Up))
        runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                d.open(warm, w.services, mts, prior = Prior.Detected(nonEmptyListOf(stale)))
            }
        }
        w.lineCalls shouldBe 1
    }

    test("подозрение в раундах до Xray: открытие досылает подтверждающий раунд само") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val suspect = Letter.LineObserved(System.currentTimeMillis(), whitelist)
        val opening = runBlocking {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                d.open(warm, w.services, mts, prior = Prior.Detected(nonEmptyListOf(suspect)))
            }
        }
        w.lineCalls shouldBe 1
        opening.board.network.shouldBeInstanceOf<NetworkCard.Measured>().scenario shouldBe Scenario.MobileWhitelist
    }

    fun recallNow(w: World, prior: Prior) = runBlocking {
        resourceScope {
            val d = w.driver()
            val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
            d.recall(warm, w.services, mts, prior) to warm
        }
    }

    val openLine = Prior.Detected(nonEmptyListOf(Letter.LineObserved(System.currentTimeMillis(), line(Reach.Up, Reach.Up))))

    test("восстановление: память этой сети знает рабочий выход → он в Xray (OverrideBalancerTarget)") {
        val probe = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val (_, warm) = recallNow(probe, Prior.None)
        val second = warm.servers[1]
        val memory = Memory.Empty.rememberSuccess(memoryKey(mts, Scenario.MobileOpen, Telegram.id), second, 1L)
        val w = World(ips = listOf("10.0.0.1", "10.0.0.2"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet(), memory = memory)
        val (recalled, _) = recallNow(w, openLine)
        recalled[Telegram.id]?.id shouldBe ServerId("s002")
        w.exits shouldContainExactly listOf("s002")
        w.saved shouldBe null
    }

    test("восстановление: памяти для этой сети нет (другой сценарий) или линии нет → выход не трогаем") {
        val probe = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val (_, warm) = recallNow(probe, Prior.None)
        val memory = Memory.Empty.rememberSuccess(memoryKey(mts, Scenario.MobileWhitelist, Telegram.id), warm.servers[0], 1L)
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet(), memory = memory)
        recallNow(w, openLine).first shouldBe emptyMap()
        recallNow(w, Prior.None).first shouldBe emptyMap()
        w.exits shouldBe emptyList()
    }

    test("восстановление: выход из памяти под блоком IP:порт — не восстанавливается") {
        val probe = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val (_, warm) = recallNow(probe, Prior.None)
        val s = warm.servers[0]
        val memory = Memory.Empty.rememberSuccess(memoryKey(mts, Scenario.MobileOpen, Telegram.id), s, 1L).block(s.endpoint, System.currentTimeMillis())
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet(), memory = memory)
        recallNow(w, openLine).first shouldBe emptyMap()
        w.exits shouldBe emptyList()
    }

    test("восстановление судит по свежести, как открытие: раунды старше LINE_FRESH_MS — не повод, выход не трогаем") {
        val probe = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        val (_, warm) = recallNow(probe, Prior.None)
        val memory = Memory.Empty.rememberSuccess(memoryKey(mts, Scenario.MobileOpen, Telegram.id), warm.servers[0], 1L)
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet(), memory = memory)
        val stale = Letter.LineObserved(System.currentTimeMillis() - podbor.core.LINE_FRESH_MS - 1_000, line(Reach.Up, Reach.Up))
        recallNow(w, Prior.Detected(nonEmptyListOf(stale))).first shouldBe emptyMap()
        w.exits shouldBe emptyList()
    }

    test("снять выход: Xray возвращается к балансировщику (OverrideBalancerTarget с пустой целью)") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = whitelist, fitsPorts = emptySet())
        runBlocking { resourceScope { val d = w.driver(); with(d) { warmUp("подписка") }; d.dropExit(Telegram.id) } }
            .shouldBeInstanceOf<Either.Right<Unit>>()
        w.exits shouldBe listOf("clear")
        w.saved shouldBe null
    }
})
