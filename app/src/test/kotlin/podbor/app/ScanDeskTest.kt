package podbor.app

import arrow.core.Either
import arrow.core.nonEmptyListOf
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import podbor.app.sub.Fetched
import podbor.app.sub.InputError
import podbor.app.sub.Subscription
import podbor.app.sub.SubscriptionInput
import podbor.app.sub.SubscriptionStore
import podbor.app.update.LATEST_RELEASE_API
import podbor.app.update.Release
import podbor.app.vpn.Desk
import podbor.app.vpn.Live
import podbor.app.vpn.ScanPorts
import podbor.app.vpn.SessionHost
import podbor.app.vpn.SessionState
import podbor.app.vpn.holdXray
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.GENERAL_FOLLOWS
import podbor.core.Handshake
import podbor.core.LineObservation
import podbor.core.Memory
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.Passport
import podbor.core.Reach
import podbor.core.Sample
import podbor.core.ScanBoard
import podbor.core.Seen
import podbor.core.ServerFacts
import podbor.core.ServerId
import podbor.core.Telegram
import podbor.core.Transport
import podbor.core.WhiteHint
import podbor.core.WhiteLists
import podbor.driver.Clock
import podbor.driver.ConfigOpts
import podbor.driver.Driver
import podbor.driver.EndpointGate
import podbor.driver.ExitProbe
import podbor.driver.InMemoryPassportStore
import podbor.driver.LineProbe
import podbor.driver.Store
import podbor.driver.XrayApi
import podbor.driver.XrayEngine
import podbor.driver.classify
import podbor.driver.detectLine

private val mts = OsNetwork.Cellular("MTS", "25001")

private fun samples(h: Handshake) = nonEmptyListOf(0, 1, 2).map { Sample("s$it", h) }

/** Сеть чистая: РФ и заграница отвечают. */
private val openLine = LineObservation(samples(Handshake.Ok(30)), samples(Handshake.Ok(30)))

/** Похоже на белые списки: РФ отвечает, заграница подменена. */
private val whiteLine = LineObservation(samples(Handshake.Ok(30)), samples(Handshake.Mirage))

private fun outbound(i: Int): JsonObject = buildJsonObject {
    put("protocol", "vless"); put("tag", "Сервер $i")
    putJsonObject("settings") { put("address", "10.0.0.${i + 1}"); put("port", 443); put("id", "00000000-0000-0000-0000-000000000000") }
}

/**
 * Сервис без Android для анализа: настоящие SessionHost и Desk, Driver на фейковых портах. Xray сессии и Xray анализа
 * отмечают подъём и остановку в [events]; оба держат один замок «Xray один на процесс». Пробы сервера с адресом [hang]
 * и эталон через SOCKS-порт [hangPort] молчат до отмены; [probeMs] — сколько отвечает проба. Сцена — в [scope] теста,
 * пул анализа и файла — [io] на том же планировщике, часы драйвера — тестовые: порядок задают шаги теста.
 */
private class ScanStand(
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    driverMs: () -> Long,
    val hang: String? = null,
    val hangPort: Int? = null,
    val probeMs: Long = 0,
    val line: LineObservation = openLine,
    val download: suspend (String) -> Fetched? = { null },
    val downloadVia: suspend (String, Int) -> Fetched? = { _, _ -> null },
    val servers: Int = 3,
    val lists: WhiteLists? = null,
    /** Сколько раз стоп сессии от анализа тут же стартует её снова (быстрое выкл→вкл, always-on). */
    var restarts: Int = 0,
    /** Сколько первых tryLock замка Xray заняты другим (рестарт успел взять Xray раньше анализа). */
    busyTries: Int = 0,
    /** Xray анализа не поднимается (конфиг, движок) — ScanFailed. */
    val warmFails: Boolean = false,
) {
    val events = mutableListOf<String>()
    val dir = Files.createTempDirectory("scan").toFile()
    val subscriptions = SubscriptionStore(dir, io = io)
    val passports = InMemoryPassportStore()
    private val lock = Mutex()
    private var busy = busyTries
    /** Замок «Xray один на процесс»; первые [busyTries] tryLock — заняты (тестовый двойник перехвата). */
    val xray: Mutex = object : Mutex by lock {
        override fun tryLock(owner: Any?): Boolean = if (busy > 0) { busy--; false } else lock.tryLock(owner)
    }
    private val lineProbe = LineProbe { line }
    private val clock = Clock { driverMs() }
    /** Часы обновления подписки — свои, ручные. */
    var nowMs = 1_000L
    /** Сколько раз Desk качал подписку. */
    var downloads = 0
    private var startId = 0

    private fun driver(): Driver = Driver(
        converter = { _ -> List(servers) { outbound(it) }.right() },
        engine = XrayEngine { _ -> Unit.right() },
        api = object : XrayApi {
            override suspend fun routeOf(ip: String) = "s001".right()
            override suspend fun setExit(balancer: String, tag: String) = Unit.right()
            override suspend fun clearExit(balancer: String) = Unit.right()
        },
        prober = { _, server, _ ->
            if (server.endpoint.host == hang) awaitCancellation()
            events += "probe ${server.endpoint.host}"
            delay(probeMs)
            classify(3, Reach.Up)
        },
        exitProbe = ExitProbe { port -> if (port == hangPort) awaitCancellation() else "198.51.100.1" },
        gate = EndpointGate(),
        resolver = { host -> host },
        line = lineProbe,
        clock = clock,
        store = object : Store {
            override fun loadMemory() = Memory.Empty
            override fun saveMemory(m: Memory) = Unit
        },
        passportStore = passports,
        tape = { },
        opts = ConfigOpts(assetDir = "/assets", services = setOf(Telegram)),
    )

    /** Xray с отметками подъёма и остановки [name]; остановка — освобождение области, где поднят. */
    private suspend fun arrow.fx.coroutines.ResourceScope.xrayOf(name: String): Either<podbor.driver.EngineError, Live> {
        install({ events += "$name up" }) { _, _ -> events += "$name down" }
        val d = driver()
        return with(d) { warmUp("подписка") }.map { Live(d, it) }
    }

    val host = SessionHost<Live>(
        scope = scope,
        bring = {
            holdXray(xray)
            xrayOf("vpn")
        },
        ended = { },
    )

    val desk = Desk(
        host,
        os = { mts },
        detect = { os, onBoard -> detectLine(lineProbe, clock, { }, os, setOf(Telegram), onBoard) },
        services = setOf(Telegram),
        xray = xray,
        scan = ScanPorts(
            download = { url -> downloads++; download(url) },
            downloadVia = downloadVia,
            servers = { servers },
            warm = { _ -> if (warmFails) Either.Left(podbor.driver.EngineError.Failed("движок")) else xrayOf("scan") },
            lists = { lists },
            subscriptions = subscriptions,
            passports = passports,
            stopSession = {
                host.stop(++startId)
                if (restarts > 0) { restarts--; host.start(++startId) }
            },
            clock = Clock { nowMs },
        ),
        io = io,
    )

    fun start() = host.start(++startId)

    suspend fun up() = host.state.first { it is SessionState.Up }

    suspend fun analyze(input: SubscriptionInput = SubscriptionInput.Text("vless://x"), onBoard: (ScanBoard) -> Unit = {}) =
        desk.analyze(input, onBoard)
}

private fun TestScope.stand(
    hang: String? = null,
    hangPort: Int? = null,
    probeMs: Long = 0,
    line: LineObservation = openLine,
    download: suspend (String) -> Fetched? = { null },
    downloadVia: suspend (String, Int) -> Fetched? = { _, _ -> null },
    servers: Int = 3,
    lists: WhiteLists? = null,
    restarts: Int = 0,
    busyTries: Int = 0,
    warmFails: Boolean = false,
) = ScanStand(
    backgroundScope, StandardTestDispatcher(testScheduler), { testScheduler.currentTime },
    hang, hangPort, probeMs, line, download, downloadVia, servers, lists, restarts, busyTries, warmFails,
)

class ScanDeskTest : FunSpec({

    test("страница подписки без VPN: замер линии мимо туннеля публикует плашку сети, сессия не поднимается") {
        runTest {
            val s = stand()
            s.desk.measureLine()
            s.desk.board.value.network.shouldBeInstanceOf<NetworkCard.Measured>()
            s.host.state.value shouldBe SessionState.Down
            s.events shouldNotContain "vpn up"
        }
    }

    test("VPN поднят → анализ сначала гасит сессию и ждёт её Xray, потом поднимает свой; после — свой остановлен") {
        runTest {
            val s = stand()
            s.start()
            s.up()
            s.analyze().shouldBeInstanceOf<Either.Right<ScanBoard>>().value.finished shouldBe true
            runCurrent()
            s.host.state.value shouldBe SessionState.Down
            s.events.filter { it.endsWith(" up") || it.endsWith(" down") } shouldBe listOf("vpn up", "vpn down", "scan up", "scan down")
        }
    }

    test("подписка сохранена (текст и ссылка); паспорт — по всем серверам") {
        runTest {
            val s = stand(download = { Fetched("vless://из ссылки") })
            s.analyze(SubscriptionInput.Link("https://a.b/sub")).shouldBeInstanceOf<Either.Right<ScanBoard>>()
            s.subscriptions.load() shouldBe Subscription("https://a.b/sub", "vless://из ссылки", fetchedAtMs = 1_000)
            s.desk.hasSubscription() shouldBe true
            s.passports.load().shouldNotBeNull().servers.size shouldBe 3
        }
    }

    test("нет серверов → NoServers, подписка не сохранена, Xray анализа не поднимался") {
        runTest {
            val s = stand(servers = 0)
            s.analyze() shouldBe Either.Left(InputError.NoServers)
            s.subscriptions.load() shouldBe null
            s.desk.hasSubscription() shouldBe false
            s.events shouldNotContain "scan up"
        }
    }

    test("ссылка не скачалась: белые списки по замеру линии — Download(true), сеть чистая — Download(false); не сохранена") {
        runTest {
            val white = stand(line = whiteLine)
            white.analyze(SubscriptionInput.Link("https://a.b/sub")) shouldBe Either.Left(InputError.Download(true))
            white.subscriptions.load() shouldBe null
            val open = stand(line = openLine)
            open.analyze(SubscriptionInput.Link("https://a.b/sub")) shouldBe Either.Left(InputError.Download(false))
        }
    }

    test("отмена анализа → Xray анализа остановлен, частичный паспорт (разобранные) сохранён") {
        // Третий сервер молчит и на эталон, и на пробы: два первых разбираются, третий висит до отмены.
        runTest {
            val s = stand(hang = "10.0.0.3", hangPort = 20002)
            val twoDone = CompletableDeferred<Unit>()
            val job = async { s.desk.analyze(SubscriptionInput.Text("vless://x")) { if (it.done == 2) twoDone.complete(Unit) } }
            twoDone.await()
            job.cancel()
            job.join()
            runCurrent()
            s.events.last() shouldBe "scan down"
            s.passports.load().shouldNotBeNull().servers.size shouldBe 2
        }
    }

    test("подсказки анализа — из белых списков по входу; нажатие при белых списках первым проверяет вероятный") {
        runTest {
            val s = stand(line = whiteLine, lists = WhiteLists.of(listOf("10.0.0.3/32"), emptyList()))
            s.analyze().shouldBeInstanceOf<Either.Right<ScanBoard>>()
            val likely = s.passports.load().shouldNotBeNull().servers.filterValues { it.whiteHint == WhiteHint.Likely }.keys
            likely.size shouldBe 1
            s.start()
            s.up()
            s.events.clear()
            s.desk.press()
            s.events.first { it.startsWith("probe") } shouldBe "probe 10.0.0.3"
        }
    }

    test("анализ один за раз: второй поднимает Xray только после остановки Xray первого") {
        runTest {
            val s = stand(probeMs = 100)
            val a = async { s.analyze() }
            val b = async { s.analyze() }
            a.await().shouldBeInstanceOf<Either.Right<ScanBoard>>()
            b.await().shouldBeInstanceOf<Either.Right<ScanBoard>>()
            s.events.filter { it.startsWith("scan ") } shouldBe listOf("scan up", "scan down", "scan up", "scan down")
        }
    }

    test("нажатие посреди анализа не идёт (сессии нет); старт VPN посреди анализа ждёт остановки Xray анализа") {
        runTest {
            val s = stand(probeMs = 300)
            val scan = async { s.analyze() }
            runCurrent()
            s.events shouldContain "scan up"
            // Анализ идёт (три пробы по 300 мс виртуального времени): нажатие вернулось, не дождавшись его, — подбора не было.
            val before = testScheduler.currentTime
            s.desk.press()
            testScheduler.currentTime shouldBe before
            s.host.state.value shouldBe SessionState.Down
            s.start()
            runCurrent()
            s.host.state.value shouldBe SessionState.Starting
            scan.await().shouldBeInstanceOf<Either.Right<ScanBoard>>()
            s.up()
            s.events.filter { it.endsWith(" up") || it.endsWith(" down") } shouldBe listOf("scan up", "scan down", "vpn up")
        }
    }

    test("старт, влетевший между стопом и замком, ждёт конца анализа: сессия поднимается после, анализ не прерван") {
        // Стоп от анализа тут же стартует сессию снова: замок уже у анализа, новая сессия ждёт его конца.
        runTest {
            val s = stand(restarts = 1)
            s.start()
            s.up()
            s.analyze().shouldBeInstanceOf<Either.Right<ScanBoard>>().value.finished shouldBe true
            s.up()
            s.events.filter { it.endsWith(" up") || it.endsWith(" down") } shouldBe
                listOf("vpn up", "vpn down", "scan up", "scan down", "vpn up")
        }
    }

    test("рестарт успел взять Xray раньше анализа → анализ гасит сессию снова, пока не возьмёт замок сам") {
        // Двойник: первые два tryLock заняты — оба раза рестарт после стопа обогнал анализ.
        runTest {
            val s = stand(restarts = 2, busyTries = 2)
            s.start()
            s.up()
            s.analyze().shouldBeInstanceOf<Either.Right<ScanBoard>>().value.finished shouldBe true
            s.up()
            s.events.filter { it.endsWith(" up") || it.endsWith(" down") } shouldBe
                listOf("vpn up", "vpn down", "vpn up", "vpn down", "scan up", "scan down", "vpn up")
        }
    }

    test("стоп сессии, ждущей замок за анализом, — Down сразу, не после анализа") {
        runTest {
            val s = stand(probeMs = 2_000)
            val scan = async { s.analyze() }
            runCurrent()
            s.events shouldContain "scan up"
            s.start()
            runCurrent()
            s.host.state.value shouldBe SessionState.Starting
            val before = testScheduler.currentTime
            s.host.stop(99)
            runCurrent()
            s.host.state.value shouldBe SessionState.Down
            testScheduler.currentTime shouldBe before
            scan.await().shouldBeInstanceOf<Either.Right<ScanBoard>>()
            s.events shouldNotContain "vpn up"
        }
    }

    test("VPN поднят, серверов нет → сессия не гасится, прежняя подписка цела") {
        runTest {
            val s = stand(servers = 0)
            s.subscriptions.save(Subscription(null, "прежняя"))
            s.start()
            s.up()
            s.analyze() shouldBe Either.Left(InputError.NoServers)
            s.host.state.value.shouldBeInstanceOf<SessionState.Up<*>>()
            s.events shouldNotContain "vpn down"
            s.subscriptions.load() shouldBe Subscription(null, "прежняя")
        }
    }

    test("VPN поднят, ссылка не скачалась → сессия не гасится, прежняя подписка цела") {
        runTest {
            val s = stand()
            s.subscriptions.save(Subscription("https://old/sub", "прежняя"))
            s.start()
            s.up()
            s.analyze(SubscriptionInput.Link("https://a.b/sub")) shouldBe Either.Left(InputError.Download(false))
            s.host.state.value.shouldBeInstanceOf<SessionState.Up<*>>()
            s.subscriptions.load() shouldBe Subscription("https://old/sub", "прежняя")
        }
    }

    test("Xray анализа не поднялся → ScanFailed, прежняя подписка цела") {
        runTest {
            val s = stand(warmFails = true)
            s.subscriptions.save(Subscription(null, "прежняя"))
            s.analyze(SubscriptionInput.Text("vless://новая")) shouldBe Either.Left(InputError.ScanFailed)
            s.subscriptions.load() shouldBe Subscription(null, "прежняя")
        }
    }

    test("подсказки нажатия — по белым спискам для всех серверов сессии, а не только из паспорта") {
        // Паспорта нет: без списков вероятный сервер не встал бы первым.
        runTest {
            val s = stand(line = whiteLine, lists = WhiteLists.of(listOf("10.0.0.3/32"), emptyList()))
            s.start()
            s.up()
            s.desk.press()
            s.events.first { it.startsWith("probe") } shouldBe "probe 10.0.0.3"
        }
    }

    test("списков нет — подсказки нажатия из паспорта") {
        runTest {
            val s = stand(line = whiteLine, lists = null)
            s.start()
            s.up()
            val key = s.host.state.value.shouldBeInstanceOf<SessionState.Up<Live>>().live.warm.servers.single { it.endpoint.host == "10.0.0.3" }.key
            s.passports.save(Passport(mapOf(key to ServerFacts(Transport.Grpc, Entry.Ru, WhiteHint.Likely, Exit.Unknown, Seen.Silent)), 1))
            s.desk.press()
            s.events.first { it.startsWith("probe") } shouldBe "probe 10.0.0.3"
        }
    }

    test("анализ по ссылке: интервал обновления — из заголовка ответа провайдера; текстом — без времени скачивания") {
        runTest {
            val s = stand(download = { Fetched("vless://x", mapOf("profile-update-interval" to "6")) })
            s.analyze(SubscriptionInput.Link("https://a.b/sub")).shouldBeInstanceOf<Either.Right<ScanBoard>>()
            s.subscriptions.load() shouldBe Subscription("https://a.b/sub", "vless://x", fetchedAtMs = 1_000, updateHours = 6)
            val t = stand()
            t.analyze(SubscriptionInput.Text("vless://y")).shouldBeInstanceOf<Either.Right<ScanBoard>>()
            t.subscriptions.load() shouldBe Subscription(null, "vless://y")
        }
    }

    test("обновление подписки: пора — новый текст и время сохранены") {
        runTest {
            val s = stand(download = { Fetched("vless://новый") })
            s.subscriptions.save(Subscription("https://a.b/sub", "vless://старый", fetchedAtMs = 0, updateHours = 1))
            s.nowMs = 3_600_000
            s.desk.refreshSubscription()
            s.subscriptions.load() shouldBe Subscription("https://a.b/sub", "vless://новый", fetchedAtMs = 3_600_000, updateHours = 1)
        }
    }

    test("обновление: не скачалась или нет серверов — остаёмся на прежней") {
        runTest {
            val old = Subscription("https://a.b/sub", "vless://старый")
            val failed = stand(download = { null })
            failed.subscriptions.save(old)
            failed.desk.refreshSubscription()
            failed.downloads shouldBe 1
            failed.subscriptions.load() shouldBe old
            val empty = stand(download = { Fetched("мусор") }, servers = 0)
            empty.subscriptions.save(old)
            empty.desk.refreshSubscription()
            empty.subscriptions.load() shouldBe old
        }
    }

    test("белые списки, VPN поднят и выход найден: подписка качается туннелем — SOCKS сервера общего выхода") {
        runTest {
            val ports = mutableListOf<Int>()
            val s = stand(line = whiteLine, download = { null }, downloadVia = { _, port -> ports += port; Fetched("vless://тоннель") })
            s.subscriptions.save(Subscription("https://a.b/sub", "vless://старый"))
            s.start()
            val up = s.up() as SessionState.Up<Live>
            s.desk.open()
            up.live.driver.exits.make(GENERAL_FOLLOWS, ServerId("s002"))
            s.desk.refreshSubscription()
            ports shouldBe listOf(ConfigOpts(assetDir = "/assets").socksBase + 1)
            s.subscriptions.load()?.text shouldBe "vless://тоннель"
        }
    }

    test("последний релиз: напрямую с GitHub; не вышло и выхода нет — null") {
        runTest {
            val body = """{"tag_name":"v0.10.0","html_url":"https://example.org/rel","assets":[]}"""
            val asked = mutableListOf<String>()
            val ok = stand(download = { url -> asked += url; Fetched(body) })
            ok.desk.latestRelease() shouldBe Release("v0.10.0", "https://example.org/rel")
            asked shouldBe listOf(LATEST_RELEASE_API)
            stand(download = { null }).desk.latestRelease() shouldBe null
        }
    }

    test("последний релиз: напрямую не вышло, VPN поднят и выход найден — туннелем") {
        runTest {
            val body = """{"tag_name":"v0.10.0","html_url":"https://example.org/rel","assets":[]}"""
            val s = stand(line = whiteLine, download = { null }, downloadVia = { _, _ -> Fetched(body) })
            s.start()
            val up = s.up() as SessionState.Up<Live>
            s.desk.open()
            up.live.driver.exits.make(GENERAL_FOLLOWS, ServerId("s002"))
            s.desk.latestRelease() shouldBe Release("v0.10.0", "https://example.org/rel")
        }
    }
})
