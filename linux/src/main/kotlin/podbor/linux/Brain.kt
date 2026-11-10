package podbor.linux

import arrow.core.Either
import arrow.fx.coroutines.ResourceScope
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import podbor.app.net.*
import podbor.app.sub.*
import podbor.app.vpn.*
import podbor.core.*
import podbor.driver.*
import podbor.remote.RemoteBrain
import podbor.screen.*
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds

private const val GEO_WAIT_MS = 3_000L
private const val WHITE_WAIT_MS = 3_000L

/** Xray Linux-сборки один и один порт API на процесс. */
private const val API_PORT = 10085

/** Значение `log.access` Xray, выключающее журнал соединений. */
private const val NO_ACCESS_LOG = "none"

/** Где что лежит и на какой адрес слушать; значения — из окружения контейнера ([configFromEnv]). */
data class LinuxConfig(
    val data: File,
    val assets: File,
    val xray: String,
    val converter: String,
    val proxyListen: String,
    val proxyPort: Int,
)

/**
 * Мозг Podbor на Linux: порядок действий — общий [Desk], платформа — прямая сеть и Xray процессом. Сессия отличается от
 * Android только входом: общий прокси ([Tun.Proxy]) вместо туннеля. Сеть для ядра — домашняя: Linux-машина не знает ни
 * оператора, ни вышки.
 */
class Brain(private val cfg: LinuxConfig, private val scope: CoroutineScope) : RemoteBrain {
    private val xray = Mutex()
    private val os: OsNetwork = OsNetwork.Wifi

    private val host = SessionHost(
        scope = scope,
        bring = {
            holdXray(xray)
            val current = subscriptions.current()
            if (current == null) Either.Left(EngineError.Failed("нет подписки"))
            else live(Tun.Proxy(cfg.proxyListen, cfg.proxyPort), current.text)
        },
        ended = {},
    )

    private val http = HttpFetch(direct = { url: URL -> url.openConnection() as HttpURLConnection })
    private val resolver = Resolver { host ->
        Either.catch { withContext(Dispatchers.IO) { InetAddress.getByName(host).hostAddress } }.getOrNull()
    }
    private val line = LineProbeImpl()
    private val tape = FileTape(cfg.data)
    private val clock = Clock { System.currentTimeMillis() }
    private val mono: () -> Long = { System.nanoTime() / 1_000_000 }

    internal val subscriptions = SubscriptionStore(cfg.data)
    private val passports = FilePassportStore(cfg.data)
    private val memory = AndroidStore(cfg.data)
    private val books = FileTrafficBookStore(cfg.data)
    private val salt by lazy { installSalt(cfg.data) }
    private val device by lazy {
        deviceHeaders(
            hwidOf(salt),
            System.getProperty("os.version").orEmpty(),
            "container",
            os = "Linux"
        )
    }

    private val converter = LibXrayCli(command = listOf(cfg.converter))
    private val geo: Deferred<GeoCountry?> = scope.async(Dispatchers.IO) {
        GeoCountry.load(File(cfg.assets, "xray/geoip.dat")).getOrNull()
    }
    private val white: Deferred<WhiteLists?> = scope.async(Dispatchers.IO) {
        Either.catch {
            fun lines(name: String) = File(cfg.assets, "whitelist/$name").readLines()
            WhiteLists.of(lines("ip.txt"), lines("sni.txt"))
        }.getOrNull()
    }

    private val filterFile = File(cfg.data, "exit-filter.json")
    private val countryChanges = Mutex()
    private val countryState = MutableStateFlow(CountrySelection(filter = readFilter()))

    private val desk = Desk(
        host = host,
        services = VPN_SERVICES,
        os = { os },
        detect = { network, onBoard -> detectLine(line, clock, tape, network, VPN_SERVICES, onBoard) },
        xray = xray,
        scan = ScanPorts(
            download = { url -> http.download(url, device) },
            downloadVia = { url, port -> http.downloadVia(url, port, device) },
            proxy = { url -> http.download(url) },
            location = http::location,
            serverKeys = { text -> converter.convert(text).fold({ emptyList() }, ::draftKeysOf) },
            warm = { text -> live(Tun.Off, text) },
            lists = { withTimeoutOrNull(WHITE_WAIT_MS.milliseconds) { white.await() } },
            subscriptions = subscriptions,
            passports = passports,
            memory = memory,
            forgot = ::forgot,
            stopSession = { host.stop(0) },
            clock = clock,
        ),
    )

    private val pulseState = MutableStateFlow(Pulse.Empty)
    private val pulseNow = MutableStateFlow(PulseView.Empty)
    private val booksLoaded = CompletableDeferred<Unit>()
    private val watching = pulseNow.subscriptionCount.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false)

    override val exitOrigin: StateFlow<Map<ServiceId, ExitOrigin>> get() = desk.origin
    override val pulse: StateFlow<PulseView> get() = pulseNow

    override val status: StateFlow<Status> =
        combine(sessionExits(), desk.board, desk.work, countryState) { (s, exit, routes), board, work, countries ->
            Status(
                s.view(), board, work, exitName(s, exit),
                countries.copy(
                    blocked = countries.blocked && s is SessionState.Up,
                    checking = s == SessionState.Starting && countries.filter.excluded.isNotEmpty(),
                ),
                routes,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            Status(host.state.value.view(), desk.board.value, countries = countryState.value)
        )

    init {
        scope.launch { refreshCountries() }
        scope.launch {
            val resumed = Pulse.resumed(books.load())
            pulseState.value = resumed
            pulseNow.value = resumed.view(emptyMap(), mono())
            booksLoaded.complete(Unit)
        }
    }

    suspend fun hasSubscription(): Boolean = desk.hasSubscription()

    /** Поднять прокси на текущей подписке. */
    override fun start() = host.start(0)

    /** Погасить прокси. */
    override fun stop() = host.stop(0)

    /** Вставленное в браузере разбирается здесь: разбор ссылок — на JVM. */
    override suspend fun parse(text: String): Either<InputError, SubscriptionInput> = inputOf(text)

    /** Подписки глазами экрана: как ведёт себя здесь каждая. */
    override suspend fun shelf(): Subscriptions = withContext(Dispatchers.IO) {
        val passport = savedPassport()
        val known = memory.loadMemory()
        val book = books.load()
        subscriptionsOf(
            subscriptions.load(), clock.nowMs(),
            factsOf = { s -> subscriptionFacts(s.servers.toSet(), passport, known, os, VPN_SERVICES.toList()) },
            viaOf = { s -> book.of(subscriptionKey(s, salt)) },
        )
    }

    /** Linux не знает системного «постоянного VPN»: On — подсказка о нём не нужна. */
    override fun alwaysOn(): AlwaysOn = AlwaysOn.On

    /** Обновлялка ставит APK; образ контейнера обновляется не здесь. */
    override suspend fun latestRelease() = null

    override suspend fun open(): Boolean = desk.open()
    override suspend fun measureLine() = desk.measureLine()
    override suspend fun press() = desk.press()

    override suspend fun refreshSubscription() {
        desk.refreshSubscription()
        refreshCountries()
    }

    override suspend fun exitCountry(service: ServiceId): String? =
        when (val result = host.act { live -> live.driver.currentExitCountry(live.warm, service) }) {
            Acted.NoSession -> null
            is Acted.Done -> result.value
        }

    override suspend fun setExitFilter(filter: ExitFilter): Unit = countryChanges.withLock {
        if (filter == countryState.value.filter) return@withLock
        saveFilter(filter)
        countryState.update { it.copy(filter = filter, blocked = false) }
        when (host.state.value) {
            SessionState.Down, is SessionState.Failed -> Unit
            SessionState.Starting, is SessionState.Up -> {
                val before = host.stops.value
                host.restart(0)
                scope.launch {
                    host.stops.first { it > before }
                    desk.repick()
                }
            }
        }
    }

    override suspend fun history(service: ServiceId): List<HistoryEvent> = withContext(Dispatchers.IO) {
        val rows = tapeFilesIn(cfg.data).sortedByDescending { it.first.startedAt }.take(2).reversed()
            .flatMap { (_, f) -> Either.catch { f.readLines() }.getOrNull().orEmpty() }
            .mapNotNull { line -> Either.catch { rowOf(line) }.getOrNull() }
        historyOf(rows, service, limit = 8)
    }

    override suspend fun analyze(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard> = desk.analyze(input, onSkipped, onBoard).also { refreshCountries() }

    override suspend fun known(input: SubscriptionInput) = desk.known(input)

    override suspend fun reanalyze(id: String, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit) =
        desk.reanalyze(id, onSkipped, onBoard).also { refreshCountries() }

    override suspend fun select(id: String): Picked = desk.select(id).also { picked ->
        when (picked) {
            Picked.Ready -> {
                switchTraffic(); refreshCountries()
            }

            is Picked.Expired -> Unit
        }
    }

    override suspend fun delete(id: String) {
        desk.delete(id)
        refreshCountries()
    }

    private fun forgot(s: Subscription, servers: Int) {
        val key = subscriptionKey(s, salt)
        pulseState.update { it.copy(book = it.book.without(key)) }
        books.save(pulseState.value.book)
        pulseNow.update { now ->
            val vpn = now.vpn
            if (vpn != null && vpn.key == key) now.copy(vpn = null) else now
        }
        Either.catch { tape.write(noteLine("subscription: удалена, забыто серверов $servers")) }
    }

    private suspend fun switchTraffic() {
        val current = subscriptions.current() ?: return
        val key = subscriptionKey(current, salt)
        pulseState.update { it.copy(book = it.book.switchedTo(key)) }
        books.save(pulseState.value.book)
        pulseNow.update { it.copy(vpn = pulseState.value.book.vpn) }
    }

    /** Паспорт с диска; пока его нет — пустой. */
    private fun savedPassport(): Passport {
        val saved = passports.load()
        return saved ?: Passport(emptyMap(), 0)
    }

    private suspend fun passportCountries(): Set<String> {
        val current = subscriptions.current() ?: return emptySet()
        val keys =
            current.servers.ifEmpty { converter.convert(current.text).fold({ emptyList() }, ::draftKeysOf) }.toSet()
        return savedPassport().servers.filterKeys { it in keys }.values.mapNotNull { facts ->
            when (val exit = facts.exit) {
                Exit.Unknown -> null
                is Exit.At -> exit.country.takeIf(::knownCountry)
            }
        }.toSet()
    }

    private suspend fun refreshCountries() {
        val before = countryState.value
        val cached = passportCountries()
        val available = when (val session = host.state.value) {
            is SessionState.Up -> if (before.filter.excluded.isEmpty()) cached else session.live.warm.countries
            SessionState.Down, SessionState.Starting, is SessionState.Failed -> cached
        }
        countryState.compareAndSet(before, before.copy(available = available))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun sessionExits(): Flow<Triple<SessionState<Live>, ServerId?, Map<ServiceId, ServerRoute>>> =
        host.state.flatMapLatest { s ->
            when (s) {
                is SessionState.Up -> {
                    val servers = s.live.warm.servers.associateBy { it.id }
                    val facts = savedPassport().servers
                    s.live.driver.exits.state.map { exits ->
                        val general = exits.general.serverOrNull()
                        val routes = VPN_SERVICES.mapNotNull { service ->
                            val pin = exits.pinned[service.id]
                            val server = servers[pin?.server ?: general]
                            if (server == null) return@mapNotNull null
                            val known = facts[server.key]
                            val exit = known?.exit ?: Exit.Unknown
                            val country = when (exit) {
                                Exit.Unknown -> ""
                                is Exit.At -> exit.country
                            }
                            service.id to ServerRoute(server.id, server.entry, country)
                        }.toMap()
                        Triple(s, general, routes)
                    }
                }

                SessionState.Down, SessionState.Starting, is SessionState.Failed -> flowOf(Triple(s, null, emptyMap()))
            }
        }

    private fun exitName(s: SessionState<Live>, exit: ServerId?): String = when (s) {
        is SessionState.Up -> {
            val server = s.live.warm.servers.find { it.id == exit }
            server?.name ?: podbor.screen.NO_EXIT
        }

        SessionState.Down, SessionState.Starting, is SessionState.Failed -> podbor.screen.NO_EXIT
    }

    /** Xray в этой области ресурсов: с общим входом-прокси (сессия) или без него ([Tun.Off] — анализ). */
    private suspend fun ResourceScope.live(tun: Tun, subscription: String): Either<EngineError, Live> {
        val opts = ConfigOpts(
            api = ApiListen.Tcp(API_PORT),
            assetDir = File(cfg.assets, "xray").absolutePath,
            tun = tun,
            services = VPN_SERVICES,
            // Входы кандидатов — на петле контейнера, соседей там нет.
            socks = SocksIn.Open,
            // Ни роста файла, ни следа, куда ходят через прокси.
            accessLog = AccessLog.File(NO_ACCESS_LOG),
        )
        val fallback = TraceExit()
        val webProbe = TraceExit("https://1.1.1.1/cdn-cgi/trace")
        val channel = xrayChannel { ManagedChannelBuilder.forAddress("127.0.0.1", API_PORT).usePlaintext().build() }
        val driver = Driver(
            converter = converter,
            engine = XrayBinary(cfg.xray, cfg.data, API_PORT),
            api = xrayApiOn(channel),
            prober = ProberImpl(
                TelegramProbeImpl(),
                InstagramProbeImpl(),
                SocketTcpReach(),
                https = webProbe::responds
            ),
            exitProbe = ExitProbeImpl(),
            gate = EndpointGate(),
            resolver = resolver,
            line = line,
            clock = clock,
            store = memory,
            passportStore = passports,
            tape = tape,
            opts = opts,
            countryOf = { ip ->
                val base = withTimeoutOrNull(GEO_WAIT_MS.milliseconds) { geo.await() }
                base?.of(ip)
            },
            exitFallback = fallback::measure,
        )
        val filter = when (tun) {
            Tun.Off -> ExitFilter()
            is Tun.On, is Tun.Proxy -> countryState.value.filter
        }
        return with(driver) { warmUp(subscription, filter) }.map { warm ->
            when (tun) {
                Tun.Off -> Unit
                is Tun.On, is Tun.Proxy -> {
                    val available = if (filter.excluded.isEmpty()) passportCountries() else warm.countries
                    countryState.update {
                        it.copy(
                            available = available,
                            blocked = filter.excluded.isNotEmpty() && warm.servers.isEmpty(),
                            unknown = warm.unknownExits,
                        )
                    }
                }
            }
            meter(driver, opts, xrayStatsOn(channel), if (tun == Tun.Off) EpochKind.Scan else EpochKind.Session)
            Live(driver, warm)
        }
    }

    private suspend fun ResourceScope.meter(driver: Driver, opts: ConfigOpts, stats: XrayStats, kind: EpochKind) {
        booksLoaded.await()
        val epoch = nextEpoch()
        val tagOf = exitTagOf(opts)
        val current = subscriptions.current()
        val subscription = if (current == null) "" else subscriptionKey(current, salt)
        install(
            {
                scope.launch {
                    pulseLoop(
                        stats = stats,
                        clock = clock,
                        mono = mono,
                        epoch = epoch,
                        context = { PulseContext(kind, networkKey(os).orEmpty(), subscription) },
                        watching = watching,
                        effective = {
                            VPN_SERVICES.mapNotNull { s ->
                                val server = driver.exits.effective(s.id).serverOrNull()
                                if (server == null) null else s.id to tagOf(server, s.id)
                            }.toMap()
                        },
                        pulse = pulseState,
                        tape = tape,
                        save = books::save,
                        emit = { pulseNow.value = it },
                    )
                }
            },
            { job, _ ->
                job.cancelAndJoin()
                books.save(pulseState.value.book)
                pulseNow.value = pulseState.value.view(emptyMap(), mono())
            },
        )
    }

    private fun nextEpoch(): Long {
        val file = File(cfg.data, "xray-epoch")
        val next = Either.catch { file.readText().trim().toLong() }.fold({ 0L }, { it }) + 1
        writeAtomic(file, next.toString())
        return next
    }

    private fun readFilter(): ExitFilter =
        Either.catch { Json.decodeFromString(ExitFilter.serializer(), filterFile.readText()) }
            .fold({ ExitFilter() }, { it })

    private fun saveFilter(filter: ExitFilter) {
        writeAtomic(filterFile, Json.encodeToString(ExitFilter.serializer(), filter))
    }
}
