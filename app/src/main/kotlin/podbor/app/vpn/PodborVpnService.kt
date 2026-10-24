package podbor.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import arrow.core.Either
import arrow.fx.coroutines.ResourceScope
import io.grpc.android.UdsChannelBuilder
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import podbor.app.net.AndroidStore
import podbor.app.net.FileTape
import podbor.app.net.Telephony
import podbor.app.net.UnderlyingNetwork
import podbor.app.redact
import podbor.app.sub.FilePassportStore
import podbor.screen.InputError
import podbor.screen.Skipped
import podbor.screen.SubscriptionInput
import podbor.app.sub.SubscriptionStore
import podbor.app.ui.MainActivity
import podbor.app.xray.GeoHolder
import podbor.screen.ServicePort
import podbor.screen.SessionView
import podbor.app.xray.LibXrayConverter
import podbor.app.xray.LibXrayEngine
import podbor.app.xray.LibXrayInvoke
import podbor.app.xray.WhiteHolder
import podbor.app.xray.geoDir
import podbor.app.xray.registerOwnerFinder
import podbor.core.Board
import podbor.core.HARDCODED_SERVICES
import podbor.core.ScanBoard
import podbor.core.ServerId
import podbor.core.ServiceId
import podbor.driver.ApiListen
import podbor.driver.Clock
import podbor.driver.ConfigOpts
import podbor.driver.Driver
import podbor.driver.EndpointGate
import podbor.driver.EngineError
import podbor.driver.ExitProbeImpl
import podbor.driver.InstagramProbeImpl
import podbor.driver.LOCAL_SOCKS
import podbor.driver.LineProbeImpl
import podbor.driver.ProberImpl
import podbor.driver.SocketTcpReach
import podbor.driver.TelegramProbeImpl
import podbor.driver.TlsHandshaker
import podbor.driver.detectLine
import podbor.driver.XrayStats
import podbor.driver.draftsOf
import podbor.driver.exitTagOf
import podbor.driver.pulseLoop
import podbor.driver.xrayApiOn
import podbor.driver.xrayChannel
import podbor.driver.xrayStatsOn
import podbor.app.sub.FileTrafficBookStore
import podbor.app.sub.deviceHeaders
import podbor.app.sub.hwidOf
import podbor.app.sub.installSalt
import podbor.app.sub.subscriptionKey
import podbor.core.EpochKind
import podbor.core.Pulse
import podbor.core.PulseView
import podbor.core.moveWords
import podbor.core.networkKey
import podbor.core.view
import podbor.driver.PulseContext
import podbor.driver.networkMoves
import podbor.screen.ExitOrigin

private const val TAG = "Podbor"
private const val CHANNEL = "vpn"
private const val NOTIFICATION_ID = 1

/** Файл Unix-сокета API Xray в noBackupFilesDir: путь короткий — у сокета предел 108 байт. */
private const val API_SOCKET = "xray-api.sock"

/** Сколько прогрев ждёт загрузку geoip.dat: дольше — идём без страны, VPN важнее. */
private const val GEO_WAIT_MS = 3_000L

/** Сколько анализ ждёт списки белых IP/SNI: дольше — подсказки Unlikely, анализ важнее. */
private const val WHITE_WAIT_MS = 3_000L

/** Номер запуска Xray сессии — эпоха счётчиков пульса: хранится, чтобы расти и через перезапуски процесса. */
private const val PULSE_PREFS = "pulse"
private const val XRAY_EPOCH = "xray_epoch"

private fun log(msg: String) {
    Log.i(TAG, msg)
}

/**
 * VPN Podbor: одна область ресурсов на сессию ([SessionHost]) — туннель, gRPC API, Xray на fd туннеля. Выход из области
 * (стоп, отзыв VPN системой, уничтожение сервиса) освобождает их в обратном порядке: Xray остановлен → fd закрыт.
 * Наружу — сводный экран ядра и два действия ядра через [LocalBinder].
 */
class PodborVpnService : VpnService(), ServicePort {

    inner class LocalBinder : Binder() {
        val service: PodborVpnService get() = this@PodborVpnService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Xray один на процесс: его держит сессия VPN всю жизнь и анализ подписки на свой прогон ([holdXray]). */
    private val xray = Mutex()

    private val host = SessionHost<Live>(
        scope = scope,
        bring = {
            holdXray(xray)
            tunSession(
                tunnel = { tunnel(Builder(), bypassable(installed(), BYPASS_APPS), packageName).map { it.fd } },
            ) { fd -> warmUp(fd) }
                .onRight { log("VPN поднят: ${it.warm.servers.size} серверов") }
                .onLeft { log("VPN не поднялся: ${redact(it.toString())}") }
        },
        ended = ::finish,
    )

    /** Нижележащая сеть (не VPN): через неё резолвятся эталоны линии и серверы подписки — и до подъёма сессии. */
    private val underlying by lazy { UnderlyingNetwork(getSystemService(ConnectivityManager::class.java)) }

    /** Линия мимо туннеля: ей не нужен Xray, поэтому она общая для замера до подъёма и для сессии. */
    private val line by lazy { LineProbeImpl(shaker = TlsHandshaker(resolve = underlying::resolve)) }

    /** Лента сервиса: замер до подъёма и сессии пишут в один файл. */
    private val tape by lazy { FileTape(File(filesDir, "tape-${System.currentTimeMillis()}.jsonl")) }

    private val clock = Clock { System.currentTimeMillis() }

    private val subscriptions by lazy { SubscriptionStore(filesDir) }
    private val passports by lazy { FilePassportStore(filesDir) }

    /** Напоминание о конце срока: при открытии экрана (после обновления подписки) и подъёме VPN — экран могут не открывать. */
    private val expiry by lazy { ExpiryNotice(this, subscriptions) { clock.nowMs() } }

    private val desk by lazy {
        Desk(
            host = host,
            os = { Telephony(this).osNetwork(underlying.transport()) },
            detect = { os, onBoard -> detectLine(line, clock, tape, os, HARDCODED_SERVICES, onBoard) },
            xray = xray,
            scan = ScanPorts(
                download = { url -> underlying.download(url, device) },
                downloadVia = { url, port -> underlying.downloadVia(url, port, device) },
                proxy = { url -> underlying.download(url) },
                location = underlying::location,
                servers = { text -> LibXrayConverter(LibXrayInvoke).convert(text).fold({ 0 }, { draftsOf(it).size }) },
                warm = { text -> live(tunFd = null, text) },
                lists = { WhiteHolder.await(WHITE_WAIT_MS) },
                subscriptions = subscriptions,
                passports = passports,
                stopSession = { host.stop(lastStartId) },
                clock = clock,
            ),
        )
    }

    /** Сводный экран ядра для Activity: замер линии до подъёма, открытие, нажатие — карточка на сервис. */
    override val board: StateFlow<Board> get() = desk.board

    /** Чьи выходы в замере открытия восстановлены — для слов «последний рабочий: … — проверяю». */
    override val exitOrigin: StateFlow<Map<ServiceId, ExitOrigin>> get() = desk.origin

    private val books by lazy { FileTrafficBookStore(filesDir) }

    /** Соль ключа подписки — своя у установки; читается в области запуска Xray (не на главном потоке). */
    private val salt by lazy { installSalt(noBackupFilesDir) }

    /** Устройство для панели провайдера — при скачивании подписки (на пуле IO, как и соль). */
    private val device by lazy { deviceHeaders(hwidOf(salt), Build.VERSION.RELEASE, Build.MODEL) }

    /** Свёртка пульса процесса: эпохи сессий и анализов подряд, поверх итогов прошлых процессов ([booksLoaded]). */
    private val pulseState = MutableStateFlow(Pulse.Empty)

    private val pulseNow = MutableStateFlow(PulseView.Empty)

    /** Итоги прошлых процессов прочитаны (в фоне, не на главном потоке): запуск Xray ждёт их — не сложить поверх пустых. */
    private val booksLoaded = CompletableDeferred<Unit>()

    /** Экран смотрит на пульс — опрос частый; открыли экран — следующий отсчёт сразу. */
    private val watching by lazy { pulseNow.subscriptionCount.map { it > 0 }.stateIn(scope, SharingStarted.Eagerly, false) }

    /** Пульс из счётчиков Xray ([pulseLoop]): темп пар живой сессии и итоги на сети и подписке; итоги — и без сессии. */
    override val pulse: StateFlow<PulseView> get() = pulseNow

    /** Подписка сохранена: без неё экран зовёт «Добавь подписку». */
    suspend fun hasSubscription(): Boolean = desk.hasSubscription()

    /** Жизнь сессии для экрана: поднимается, поднята, не поднялась, нет. Что внутри поднятой — экрану не нужно. */
    override val session: StateFlow<SessionView> get() = host.view

    /** Номер последней команды Android: отзыв VPN системой приходит без своего. */
    @Volatile
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        GeoHolder.start { File(geoDir(this), "geoip.dat") }
        WhiteHolder.start(this)
        scope.launch {
            val resumed = Pulse.resumed(books.load())
            pulseState.value = resumed
            pulseNow.value = resumed.view(emptyMap(), SystemClock.elapsedRealtime())
            booksLoaded.complete(Unit)
        }
        registerOwnerFinder(getSystemService(ConnectivityManager::class.java))
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        // Уведомление следует за выходом общего балансера: имя — из подписки по подтверждённому Xray тегу; без сессии
        // не показывается.
        scope.launch {
            sessionExits().map { (s, e) -> s to exitName(s, e) }.distinctUntilChanged().collect { (s, name) ->
                when (s) {
                    is SessionState.Up -> {
                        nm.notify(NOTIFICATION_ID, notification(name))
                        launch { expiry.check() }
                    }
                    SessionState.Starting -> nm.notify(NOTIFICATION_ID, notification(name))
                    SessionState.Down, is SessionState.Failed -> Unit
                }
            }
        }
        // Сеть сменилась на поднятой сессии — перемер под новую сеть, туннель не рвётся ([Desk.open]). Новый переезд
        // посреди перемера отменяет устаревший. Пока идёт — строка переезда в уведомлении.
        scope.launch {
            val networks = underlying.changes().map { telephony.osNetworkLight(underlying.transport()) }
            networkMoves(networks, host.state.map { it is SessionState.Up }).collectLatest { move ->
                log("сеть сменилась: ${networkKey(move.from)} → ${networkKey(move.to)}, перемеряю")
                nm.notify(NOTIFICATION_ID, notification(currentExitName(), "${moveWords(move)} — проверяю выходы"))
                try {
                    desk.open()
                } finally {
                    if (host.state.value is SessionState.Up) nm.notify(NOTIFICATION_ID, notification(currentExitName()))
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == SERVICE_INTERFACE) super.onBind(intent) else binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Лепесток «выкл» на экране: стоп сессии той же дорогой, что отзыв VPN системой.
        if (intent?.action == ACTION_STOP) {
            log("стоп: выключен с экрана")
            host.stop(startId)
            return START_NOT_STICKY
        }
        // Прочие команды — старт: явный старт экрана или перезапуск системой (intent == null). Стоп — лепесток экрана
        // (ACTION_STOP выше) или отзыв VPN системой (onRevoke); сервис не экспортирован, чужих команд нет.
        startForeground()
        host.start(startId)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        log("onRevoke: VPN отозван системой")
        host.stop(lastStartId)
    }

    override fun onDestroy() {
        // Отмена области сервиса отменяет сессию: release Arrow выполняется и при отмене.
        scope.cancel()
        super.onDestroy()
    }

    /** Экран открыт: см. [Desk.open]. `false` — замера не было, экран откроет его на следующем подъёме. */
    override suspend fun open(): Boolean = desk.open()

    /** Плашка сети без VPN (страница подписки): см. [Desk.measureLine]. */
    override suspend fun measureLine() = desk.measureLine()

    /** Тихое обновление подписки по ссылке. */
    /** Тихое обновление подписки ([Desk.refreshSubscription]), потом — напоминание о конце срока по свежим сведениям. */
    override suspend fun refreshSubscription() {
        desk.refreshSubscription()
        expiry.check()
    }

    override suspend fun latestRelease() = desk.latestRelease()

    /** Нажатие: см. [Desk.press]. */
    override suspend fun press() = desk.press()

    /** Анализ подписки: см. [Desk.analyze] — VPN гаснет на время анализа, Xray анализа без TUN. */
    override suspend fun analyze(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard> = desk.analyze(input, onSkipped, onBoard)

    /** Сессия и выход общего балансера в ней ([podbor.driver.ExitsState.general]): у новой сессии — свои выходы. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun sessionExits(): Flow<Pair<SessionState<Live>, ServerId?>> = host.state.flatMapLatest { s ->
        when (s) {
            is SessionState.Up -> s.live.driver.exits.state.map { s to it.general }
            SessionState.Down, SessionState.Starting, is SessionState.Failed -> flowOf(s to null)
        }
    }

    /** Сессия VPN: Xray на fd туннеля с сохранённой подпиской; её нет — сессия Failed «нет подписки». */
    private suspend fun ResourceScope.warmUp(fd: Int): Either<EngineError, Live> {
        // Подписку человека кладёт экран входа; её текст секретен и в лог не идёт.
        val subscription = subscriptions.load() ?: return Either.Left(EngineError.Failed("нет подписки"))
        return live(fd, subscription.text)
    }

    /** Xray в этой области ресурсов: на fd туннеля (сессия) или без TUN ([tunFd] = null — анализ, пробы через SOCKS). */
    private suspend fun ResourceScope.live(tunFd: Int?, subscription: String): Either<EngineError, Live> {
        // API Xray — Unix-сокет в личной папке: порт на 127.0.0.1 открыт любому приложению на телефоне, каталог — нет.
        // Сокет от убитого процесса Xray не снимает сам («address already in use») — убираем до старта.
        val apiSocket = File(noBackupFilesDir, API_SOCKET).apply { delete() }
        val opts = ConfigOpts(
            assetDir = geoDir(this@PodborVpnService).absolutePath,
            tunFd = tunFd,
            services = HARDCODED_SERVICES,
            uids = HARDCODED_SERVICES.flatMap { it.match.apps }.mapNotNull { pkg -> uidOf(pkg)?.let { pkg to it } }.toMap(),
            socksAuth = LOCAL_SOCKS,
            api = ApiListen.Unix(apiSocket.path),
        )
        // Страна входа — из geoip.dat, загруженного один раз на процесс; не готов вовремя или не загрузился — «?».
        val geo = GeoHolder.await(GEO_WAIT_MS)
        val channel = xrayChannel { UdsChannelBuilder.forPath(apiSocket.path, LocalSocketAddress.Namespace.FILESYSTEM).build() }
        val driver = Driver(
            converter = LibXrayConverter(LibXrayInvoke),
            engine = LibXrayEngine(LibXrayInvoke).reporting { log("Xray остановлен") },
            api = xrayApiOn(channel).reporting { log("API Xray: $it") },
            prober = ProberImpl(TelegramProbeImpl(auth = LOCAL_SOCKS), InstagramProbeImpl(), SocketTcpReach()),
            exitProbe = ExitProbeImpl(),
            gate = EndpointGate(),
            resolver = underlying.resolver,
            line = line,
            clock = clock,
            store = AndroidStore(filesDir),
            passportStore = passports,
            tape = tape,
            opts = opts,
            countryOf = { ip -> geo?.of(ip) },
        )
        return with(driver) { warmUp(subscription) }.map { warm ->
            meter(driver, opts, xrayStatsOn(channel), if (tunFd != null) EpochKind.Session else EpochKind.Scan)
            Live(driver, warm)
        }
    }

    /**
     * Пульс запуска Xray (сессии или анализа) в его области: опрос счётчиков в общую свёртку процесса ([pulseState]) до
     * конца области; потом — итоги без пар (темп только у живой сессии). Эпоха — номер запуска Xray (растёт и через
     * перезапуски процесса); время темпа — монотонное. Ключи итогов: сеть — по сети доски экрана (с ней же сверяется
     * «СЕТЬ»), подписка — сохранённая: анализ чужой подписки итог «через VPN» не сбрасывает, новая сессия на новой — да.
     * Частый опрос — пока экран подписан на [pulse].
     */
    private suspend fun ResourceScope.meter(driver: Driver, opts: ConfigOpts, stats: XrayStats, kind: EpochKind) {
        booksLoaded.await()
        val epoch = nextEpoch()
        val tagOf = exitTagOf(opts)
        val subscription = subscriptions.load()?.let { subscriptionKey(it, salt) } ?: ""
        install(
            {
                scope.launch {
                    pulseLoop(
                        stats = stats,
                        clock = clock,
                        mono = SystemClock::elapsedRealtime,
                        epoch = epoch,
                        // Сеть — ОС на момент отсчёта (доска экрана без открытого экрана смену сети не видит).
                        context = { PulseContext(kind, networkKey(telephony.osNetworkLight(underlying.transport())) ?: "", subscription) },
                        watching = watching,
                        effective = {
                            HARDCODED_SERVICES.mapNotNull { s -> driver.exits.effective(s.id)?.let { s.id to tagOf(it, s.id) } }.toMap()
                        },
                        pulse = pulseState,
                        tape = tape,
                        save = books::save,
                        emit = { pulseNow.value = it },
                    )
                }
            },
            { job, _ ->
                // Дождаться петли: отменённая между ответом и выдачей, она затёрла бы вид после неё.
                job.cancelAndJoin()
                books.save(pulseState.value.book)
                pulseNow.value = pulseState.value.view(emptyMap(), SystemClock.elapsedRealtime())
            },
        )
    }

    private val telephony by lazy { Telephony(this) }

    private fun nextEpoch(): Long {
        val prefs = getSharedPreferences(PULSE_PREFS, MODE_PRIVATE)
        val next = prefs.getLong(XRAY_EPOCH, 0) + 1
        prefs.edit().putLong(XRAY_EPOCH, next).apply()
        return next
    }

    /** UID пакета или null, если не установлен (виден благодаря `<queries>` манифеста). */
    private fun uidOf(pkg: String): Int? = Either.catch { packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()

    /** Установленные из вшитого списка: на Android 11+ видимы благодаря `<queries>` манифеста. */
    private fun installed(): Set<String> = BYPASS_APPS.filter { name ->
        Either.catch { packageManager.getPackageInfo(name, 0) }.isRight()
    }.toSet()

    /**
     * Сессия кончилась (стоп, отзыв или неудачный подъём) командой [startId]: Android останавливает сервис, только если
     * после неё команд не было; иначе следующий старт уже в очереди хоста — foreground остаётся ему.
     */
    private fun finish(startId: Int) {
        if (stopSelfResult(startId)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            log("сервис остановлен")
        } else {
            log("сервис остаётся: после стопа пришла новая команда")
        }
    }

    private fun startForeground() {
        val n = notification(currentExitName())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    /** Имя общего выхода сессии сейчас — для уведомления. */
    private fun currentExitName(): String = when (val s = host.state.value) {
        is SessionState.Up -> exitName(s, s.live.driver.exits.state.value.general)
        SessionState.Down, SessionState.Starting, is SessionState.Failed -> exitName(s, null)
    }

    private fun exitName(s: SessionState<Live>, exit: ServerId?): String = when (s) {
        is SessionState.Up -> s.live.warm.servers.find { it.id == exit }?.name ?: "выход не выбран"
        SessionState.Down, SessionState.Starting, is SessionState.Failed -> "выход не выбран"
    }

    /** [text] — что сессия делает сейчас (перемер после переезда сети); нет — только выход. */
    private fun notification(exitName: String, text: String? = null): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Podbor · $exitName")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            // Тап по уведомлению — экран A (singleTask: тот же экран, не второй).
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        const val ACTION_START = "podbor.app.vpn.START"
        const val ACTION_STOP = "podbor.app.vpn.STOP"
    }
}
