package podbor.app.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import arrow.core.Either
import arrow.core.raise.either
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import podbor.app.BuildConfig
import podbor.app.R
import podbor.app.share.bundleOf
import podbor.app.share.readTapes
import podbor.app.share.wordsOf
import podbor.app.share.writeShare
import podbor.app.sub.SubscriptionInput
import podbor.app.sub.SubscriptionNote
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.inputOf
import podbor.app.sub.noteOf
import podbor.app.update.Offer
import podbor.app.update.Release
import podbor.app.update.checkDue
import podbor.app.update.offerOf
import podbor.app.vpn.PodborVpnService
import podbor.core.Board
import podbor.core.CardStatus
import podbor.core.HARDCODED_SERVICES
import podbor.core.NetworkCard
import podbor.core.ServiceId
import podbor.core.board
import podbor.screen.LinkFacts
import podbor.screen.SessionView
import podbor.screen.Start
import podbor.screen.keepLast
import podbor.screen.ExitOrigin

private const val TAG = "Podbor"
private const val PREFS = "podbor"
private const val ASKED = "permissions_asked"
private const val REFUSED = "vpn_refused"
private const val PHASE = "scan_phase"
private const val DISCLOSING = "location_disclosing"
private const val UPDATE_CHECKED = "update_checked_at"
private const val UPDATE_TAG = "update_tag"
private const val UPDATE_URL = "update_url"

private fun log(msg: String) {
    Log.i(TAG, msg)
}

/**
 * Экран A: сеть сверху (фрагмент, всегда), под ней страница по [pageOf] — «Добавь подписку»/анализ или главный.
 * Открытие (холодный старт или возврат из фона) — разрешения при первом открытии; на главном — согласие на VPN, сервис
 * (поднимается, если не запущен) и `open()`; без подписки VPN не поднимается — только замер линии для плашки сети.
 * Экран и жизнь сессии — последние, что сказал сервис: его уход после стопа не превращается в «поднимаю VPN…».
 * Состояние страниц — здесь и в сервисе (потоки), фрагменты только читают.
 */
class MainActivity : FragmentActivity() {
    private val service = MutableStateFlow<PodborVpnService?>(null)
    private var bound = false

    /**
     * Состояние экрана, а не подписки: факты о VPN и последний экран сервиса. Заполняются сборщиком на всю жизнь
     * Activity — и пока экран в фоне (иначе Down при отзыве из Настроек никто бы не увидел).
     */
    private val knownFacts = MutableStateFlow(LinkFacts())
    private val lastBoard = MutableStateFlow(board(HARDCODED_SERVICES, emptyMap()))
    private val lastOrigin = MutableStateFlow<Map<ServiceId, ExitOrigin>>(emptyMap())

    internal val facts: StateFlow<LinkFacts> = knownFacts.asStateFlow()
    internal val boards: StateFlow<Board> = lastBoard.asStateFlow()
    internal val origin: StateFlow<Map<ServiceId, ExitOrigin>> = lastOrigin.asStateFlow()

    /** Сохранена ли подписка; null — ещё не прочитали (страницы нет). */
    private val hasSubscription = MutableStateFlow<Boolean?>(null)
    internal val subscribed: StateFlow<Boolean?> = hasSubscription.asStateFlow()

    private val scan = MutableStateFlow<ScanPhase>(ScanPhase.Idle)
    internal val phase: StateFlow<ScanPhase> = scan.asStateFlow()

    /** Идущий анализ: «ОК, дальше» и «назад» его прерывают (частичный паспорт сохраняет драйвер). */
    private var scanJob: Job? = null

    /** «ОК» уже нажато: выход с анализа идёт. */
    private var finishing = false

    private val subscriptions by lazy { SubscriptionStore(filesDir) }

    /** Что показать о подписке в «⋮»: пересчитывается, когда подписка прочитана заново. */
    private val subNote = MutableStateFlow<SubscriptionNote?>(null)
    internal val subscriptionNote: StateFlow<SubscriptionNote?> = subNote.asStateFlow()

    /** Снять плашку сети без VPN, как только сервис привязан (страница подписки). */
    private var linePending = false

    /** Замерить сеть, как только сессия поднята: на каждом открытии и после прерванного замера. */
    private var openPending = false
    private var openedAt = 0L

    /** Идёт системный диалог (разрешения или согласие на VPN): возврат из него не новое открытие. */
    private var asking = false

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        asking = false
        // Спрошено — когда ответ получен: диалог, прерванный уходом экрана, спросится снова.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        log("разрешения: " + granted.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
        afterPermissions()
    }

    /** Есть новее на GitHub — что предложить (чип в строке сети); нет или сборка не с GitHub — null. */
    private val offer = MutableStateFlow<Offer?>(null)
    internal val update: StateFlow<Offer?> = offer.asStateFlow()

    /** Шторка «Зачем Podbor геолокация» открыта: ждёт ответа до системного запроса. */
    private val disclosing = MutableStateFlow(false)

    /** Почему Ленту не отдали (слова под «поделиться Лентой»); null — нечего сказать. */
    private val note = MutableStateFlow<String?>(null)
    internal val shareNote: StateFlow<String?> = note.asStateFlow()

    /** Сканер QR (zxing): текст QR — тем же путём, что буфер. Возврат из него — не новое открытие. */
    private val qr = registerForActivityResult(ScanContract()) { r ->
        asking = false
        r.contents?.let(::input)
    }

    /** Системный выбор «куда отправить»: возврат из него — не новое открытие (замер сети не повторяется). */
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        asking = false
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        asking = false
        if (r.resultCode == RESULT_OK) {
            startVpn()
        } else {
            knownFacts.update { it.refused() }
            // VPN не будет — сервис экрану держать незачем.
            release()
            log("согласие на VPN не дано")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            knownFacts.update { it.bound() }
            service.value = (binder as PodborVpnService.LocalBinder).service
            openIfPending()
            lineIfPending()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            knownFacts.update { it.unbound() }
            service.value = null
        }

        /** Привязка умерла насовсем: отвязаться и привязаться заново — к следующему запуску сервиса. */
        override fun onBindingDied(name: ComponentName) {
            log("экран: привязка к сервису умерла — привязываюсь заново")
            unbindService(this)
            bound = false
            knownFacts.update { it.unbound() }
            service.value = null
            bindIfStarted()
        }
    }

    /**
     * Держит сервис, пока VPN нет, а нужен анализ или плашка сети (страница подписки): привязка с BIND_AUTO_CREATE.
     * Сервис ею создаётся без foreground и без VPN; основная привязка ([connection], без создания) подключается к нему.
     * Отпускается, когда стартует VPN (дальше сервис держит старт) и с уходом экрана.
     */
    private val keeper = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // Основная привязка подключается к созданному сервису сама; это — на случай, если она опоздала.
            if (service.value == null) service.value = (binder as PodborVpnService.LocalBinder).service
            lineIfPending()
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    private var kept = false

    /** «назад»: на анализе — прервать его (частичное сохраняется); на странице подписки при сохранённой — к главному. */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (pageOf(hasSubscription.value == true, scan.value)) {
                is Page.Scanning -> finishScan()
                Page.AddSubscription -> backToMain()
                Page.Main -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема A всегда тёмная: значки строк состояния и навигации — светлые при любой теме системы.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.elapsedRealtime()
        if (savedInstanceState?.getBoolean(REFUSED) == true) knownFacts.update { it.refused() }
        lifecycleScope.launch { service.keepLast { it.session }.collect { s -> knownFacts.update { it.seen(s) } } }
        lifecycleScope.launch { service.keepLast { it.board }.collect { lastBoard.value = it } }
        lifecycleScope.launch { service.keepLast { it.exitOrigin }.collect { lastOrigin.value = it } }
        setContentView(R.layout.activity_main)
        // Последний известный релиз — сразу: чип виден и до новой проверки, и без сети.
        if (BuildConfig.SELF_DISTRIBUTED) offer.value = offerOf(BuildConfig.VERSION_NAME, knownRelease())
        // Хозяин шторки геолокации: сама шторка — своё окно поверх страниц, месту в разметке не нужна.
        addContentView(
            ComposeView(this).apply {
                setContent {
                    val open by disclosing.collectAsStateWithLifecycle()
                    if (open) PodborTheme { LocationSheet(onAllow = { disclosed(true) }, onDecline = { disclosed(false) }) }
                }
            },
            ViewGroup.LayoutParams(0, 0),
        )
        onBackPressedDispatcher.addCallback(this, back)
        // Привязка без создания — сразу: к сервису, созданному стартом VPN или анализом, экран подключится сам.
        bindIfStarted()
        watchUpdates()
        watch(lastBoard)
        showPages()
        if (savedInstanceState == null) {
            opening("холодный старт")
            shared(intent)
        } else {
            restore(savedInstanceState.getString(PHASE))
            // Шторку пересоздание не закрывает: ответа ещё нет.
            if (savedInstanceState.getBoolean(DISCLOSING)) {
                asking = true
                disclosing.value = true
            }
        }
    }

    /** singleTask: «поделиться → Podbor» при открытом экране приходит сюда. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    override fun onRestart() {
        super.onRestart()
        if (!asking) opening("возврат")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(REFUSED, knownFacts.value.start == Start.Refused)
        outState.putString(PHASE, phaseKind(scan.value))
        outState.putBoolean(DISCLOSING, disclosing.value)
    }

    /** Вид фазы для Bundle: доски и ошибки не сохраняем — они выводятся заново. */
    private fun phaseKind(p: ScanPhase): String = when (p) {
        ScanPhase.Idle -> "idle"
        ScanPhase.Choosing -> "choosing"
        is ScanPhase.Running, is ScanPhase.Done, is ScanPhase.Failed -> "scan"
    }

    /**
     * Пересоздание Activity: «Сменить подписку» остаётся выбором; прерванный пересозданием анализ (или неудачная
     * попытка — она могла погасить VPN) — заново через открытие: есть подписка — главный и VPN, нет — «Добавь подписку».
     */
    private fun restore(kind: String?) {
        when (kind) {
            "choosing" -> scan.value = ScanPhase.Choosing
            "scan" -> {
                opening("пересоздание")
                return
            }
        }
        lifecycleScope.launch { hasSubscription.value = loadSubscription() }
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        bound = false
        release()
        super.onDestroy()
    }

    /**
     * Новое открытие экрана: метка для замера, разрешения (один раз), дальше — по странице: на главном VPN и замер
     * сети, на странице подписки — только замер линии (VPN без подписки не поднимается), на анализе — ничего.
     */
    private fun opening(why: String) {
        openedAt = SystemClock.elapsedRealtime()
        log("экран: открыт ($why); от старта процесса ${openedAt - Process.getStartElapsedRealtime()} мс")
        lifecycleScope.launch {
            val has = loadSubscription()
            hasSubscription.value = has
            when (pageOf(has, scan.value)) {
                Page.Main -> openPending = true
                Page.AddSubscription -> linePending = true
                is Page.Scanning -> return@launch
            }
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ASKED, false)) {
                afterPermissions()
            } else {
                // Сначала шторка — зачем геолокация; системный запрос — только после ответа в ней.
                asking = true
                disclosing.value = true
            }
        }
    }

    /** Ответ в шторке геолокации → системный запрос (отказ — без геолокации; уведомления спрашиваются всё равно). */
    private fun disclosed(locationAllowed: Boolean) {
        // Второй ответ той же шторки — не второй запрос разрешений.
        if (!disclosing.value) return
        disclosing.value = false
        val wanted = permissionsFor(locationAllowed, Build.VERSION.SDK_INT)
        log("шторка геолокации: " + if (locationAllowed) "разрешить" else "не сейчас")
        if (wanted.isEmpty()) {
            asking = false
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
            afterPermissions()
        } else {
            permissions.launch(wanted.toTypedArray())
        }
    }

    /** Разрешения спрошены: на главном — VPN, на странице подписки — плашка сети. */
    private fun afterPermissions() {
        when (pageOf(hasSubscription.value == true, scan.value)) {
            Page.Main -> ensureVpn()
            Page.AddSubscription -> lineIfPending()
            is Page.Scanning -> Unit
        }
    }

    /**
     * Страница под сетью — по [pageOf]: смена вида страницы — транзакция FragmentManager (без back stack; «назад» —
     * [back]). Пока экран виден: транзакции после onSaveInstanceState запрещены, а вернувшись, экран догонит последнюю.
     */
    private fun showPages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(hasSubscription.filterNotNull(), scan, ::pageOf).collect { page ->
                    back.isEnabled = when (page) {
                        is Page.Scanning -> true
                        Page.AddSubscription -> hasSubscription.value == true
                        Page.Main -> false
                    }
                    val kind = when (page) {
                        Page.Main -> MainFragment::class.java
                        Page.AddSubscription, is Page.Scanning -> SubscriptionFragment::class.java
                    }
                    if (page !is Page.Main) keep()
                    if (supportFragmentManager.findFragmentById(R.id.page)?.javaClass != kind) {
                        log("экран: страница ${kind.simpleName}")
                        supportFragmentManager.beginTransaction()
                            .setReorderingAllowed(true)
                            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                            .replace(R.id.page, kind, null)
                            .commit()
                    }
                }
            }
        }
    }

    private fun keep() {
        if (!kept) kept = bindService(Intent(this, PodborVpnService::class.java), keeper, BIND_AUTO_CREATE)
    }

    private fun release() {
        if (kept) unbindService(keeper)
        kept = false
    }

    private fun lineIfPending() {
        val s = service.value ?: return
        if (!linePending || asking) return
        linePending = false
        lifecycleScope.launch { s.measureLine() }
    }

    /** Текст подписки (буфер, QR, «поделиться»): не принялся — словами на странице подписки, иначе — анализ. */
    internal fun input(raw: String) {
        // Посреди анализа новый вход (поделиться, QR) не перебивает идущий и не подменяет его доску ошибкой.
        if (scanJob?.isActive == true) return
        when (val r = inputOf(raw)) {
            is Either.Left -> scan.value = ScanPhase.Failed(r.value)
            is Either.Right -> analyze(r.value)
        }
    }

    /**
     * Анализ в сервисе (VPN гаснет на его время): доски — из IO в [scan], экран их только читает. Один за раз: вход,
     * пришедший посреди, не перебивает идущий.
     */
    private fun analyze(input: SubscriptionInput) {
        if (scanJob?.isActive == true) return
        scan.value = ScanPhase.Running(null)
        keep()
        log("подписка: анализ начат")
        scanJob = lifecycleScope.launch {
            val s = service.filterNotNull().first()
            val out = s.analyze(input) { b -> scan.update { if (it is ScanPhase.Running) ScanPhase.Running(b) else it } }
            hasSubscription.value = loadSubscription()
            scan.value = when (out) {
                is Either.Left -> ScanPhase.Failed(out.value)
                is Either.Right -> ScanPhase.Done(out.value)
            }
            log("подписка: анализ " + if (out.isRight()) "кончился" else "не принялся")
        }
    }

    /**
     * «ОК» (или «ОК, дальше», «назад» на анализе): идущий анализ прерывается — драйвер сохраняет частичный паспорт;
     * подписка сохранена — главный и VPN, как при открытии; не успела сохраниться — снова «Добавь подписку».
     */
    internal fun finishScan() {
        // Двойное «ОК» — один выход: второе ждало бы ту же отмену и открывало бы экран повторно.
        if (finishing) return
        finishing = true
        lifecycleScope.launch {
            scanJob?.cancelAndJoin()
            scanJob = null
            val has = loadSubscription()
            hasSubscription.value = has
            scan.value = ScanPhase.Idle
            finishing = false
            if (has) opening("подписка принята")
        }
    }

    /** Подписка прочитана заново: есть ли она; заметка для «⋮» — по ней же. */
    private suspend fun loadSubscription(): Boolean {
        val s = subscriptions.load()
        subNote.value = s?.let { noteOf(it, System.currentTimeMillis()) }
        return s != null
    }

    /** «⋮» → «Поддержка провайдера»: ссылка провайдера — браузером или его приложением; открыть нечем — молча. */
    /** Чип «обновить до …»: ссылка на APK — качает и ставит сам Android. */
    internal fun openLink(url: String) {
        log("экран: обновить")
        Either.catch { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    /** «Поддержать»: страница проекта на Boosty. */
    internal fun donate() {
        log("экран: поддержать")
        Either.catch { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(BuildConfig.DONATE_URL))) }
    }

    internal fun openSupport(url: String) {
        log("экран: поддержка провайдера")
        Either.catch { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    /** «⋮» → «Сменить подписку». VPN не трогаем: его погасит анализ новой подписки, если до него дойдёт. */
    internal fun changeSubscription() {
        scan.value = ScanPhase.Choosing
    }

    /** «назад» со страницы подписки при сохранённой: к главному; анализ мог погасить VPN — открытие его поднимет. */
    private fun backToMain() {
        val tried = scan.value is ScanPhase.Failed
        scan.value = ScanPhase.Idle
        // Попытка могла погасить VPN — открытие его поднимет (и отпустит привязку в startVpn); иначе VPN как был —
        // сервис держит его старт, экрану держать незачем.
        if (tried) opening("назад к главному") else release()
    }

    /** Кнопка «QR»: сканер zxing; возврат из него — не новое открытие. */
    internal fun scanQr() {
        asking = true
        qr.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("наведи на QR подписки")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    /** «Поделиться → Podbor» (SEND text/plain): тот же путь, что буфер. Текст секретен — в лог только факт. */
    private fun shared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        // Повторная доставка того же Intent (пересоздание) не запускает анализ снова.
        setIntent(Intent(this, MainActivity::class.java))
        log("подписка: пришла через «поделиться»")
        input(text)
    }

    private fun ensureVpn() {
        when (val ask = VpnService.prepare(this)) {
            null -> startVpn()
            is Intent -> {
                asking = true
                knownFacts.update { it.askingConsent() }
                consent.launch(ask)
            }
        }
    }

    private fun startVpn() {
        knownFacts.update { it.issued() }
        val intent = Intent(this, PodborVpnService::class.java)
        startForegroundService(Intent(intent).setAction(PodborVpnService.ACTION_START))
        bindIfStarted()
        // Дальше сервис держит старт: экран его живым не держит (как до страницы подписки).
        release()
        openIfPending()
    }

    /** Привязка без BIND_AUTO_CREATE: экран не держит сервис живым и не создаёт его сам. */
    private fun bindIfStarted() {
        if (!bound) bound = bindService(Intent(this, PodborVpnService::class.java), connection, 0)
    }

    private fun openIfPending() {
        val s = service.value ?: return
        if (!openPending) return
        openPending = false
        // Действие живёт в сессии сервиса: уход экрана отменяет только ожидание, не замер.
        // Замера не было (быстрое выкл→вкл, неудачный подъём) — ждём следующего подъёма.
        lifecycleScope.launch {
            if (!s.open()) openPending = true
            s.refreshSubscription()
            loadSubscription()
        }
    }

    /**
     * Пока экран виден — спросить о релизе при подключении к сервису и каждой смене сессии (VPN поднялся — туннель для
     * белых списков). Один поток — проверки идут по очереди; новая смена отменяет начатую.
     */
    private fun watchUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { service.keepLast { s -> s.session.map { s } }.collectLatest(::checkUpdate) }
        }
    }

    /**
     * Спросить GitHub о последнем релизе — не чаще [checkDue]; ответ запоминается, чип — по нему. Не вышло — время не
     * ставим: спросим при следующем открытии.
     */
    private suspend fun checkUpdate(s: PodborVpnService) {
        if (!BuildConfig.SELF_DISTRIBUTED) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!checkDue(prefs.getLong(UPDATE_CHECKED, -1).takeIf { it >= 0 }, now)) return
        val got = s.latestRelease() ?: return
        prefs.edit().putLong(UPDATE_CHECKED, now).putString(UPDATE_TAG, got.tag).putString(UPDATE_URL, got.url).apply()
        log("обновлялка: на GitHub ${got.tag}")
        offer.value = offerOf(BuildConfig.VERSION_NAME, got)
    }

    private fun knownRelease(): Release? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tag = prefs.getString(UPDATE_TAG, null) ?: return null
        val url = prefs.getString(UPDATE_URL, null) ?: return null
        return Release(tag, url)
    }

    /** «повторить» после неудачного подъёма: тот же путь, что при входе — согласие могли отозвать, prepare спросит. */
    internal fun retry() {
        log("экран: повторить подъём")
        ensureVpn()
    }

    /** Лепесток «выкл»: сессию останавливает сервис — тем же путём, что отзыв VPN системой. */
    internal fun turnOff() {
        log("экран: выключить VPN")
        startService(Intent(this, PodborVpnService::class.java).setAction(PodborVpnService.ACTION_STOP))
    }

    /** «включить» после выключения: как открытие экрана — подъём и замер открытия на поднятой сессии. */
    internal fun turnOn() = opening("включить")

    internal fun press() {
        val s = service.value ?: return
        log("экран: нажатие")
        lifecycleScope.launch { s.press() }
    }

    /**
     * «Поделиться Лентой»: ленты за последние 7 дней одним файлом в cache/share/ → системный шаринг через FileProvider.
     * Отдавать нечего — говорим словами.
     */
    internal fun share() {
        note.value = null
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                either {
                    writeShare(cacheDir, bundleOf(readTapes(filesDir, System.currentTimeMillis()).bind(), LocalDateTime.now()).bind()).bind()
                }
            }
            when (out) {
                is Either.Left -> {
                    note.value = wordsOf(out.value)
                    log("лента: не отдана — ${out.value}")
                }
                is Either.Right -> send(out.value)
            }
        }
    }

    private fun send(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.tape", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        log("лента: отдаю ${file.length()} байт")
        asking = true
        chooser.launch(Intent.createChooser(send, "Поделиться Лентой"))
    }

    /**
     * Пока экран виден: подъём сессии открывает отложенный замер; метки в logcat — без имён и адресов
     * (смена кнопки, плашка сети после открытия).
     */
    private fun watch(boards: Flow<Board>) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    service.keepLast { it.session }.collect { if (it == SessionView.Up) openIfPending() }
                }
                launch {
                    boards.map { it.button }.distinctUntilChanged().collect { log("экран: кнопка $it") }
                }
                launch {
                    // Ярус «текущий VPN» — только вид состояния и откуда выход по сервисам, без имён: метка для замера на телефоне.
                    combine(boards, lastOrigin) { b, origin ->
                        b.cards.joinToString(" ") { "${it.label}:${kindOf(it.status)}/${origin[it.service] ?: ExitOrigin.Chosen}" }
                    }
                        .distinctUntilChanged()
                        .collect { log("экран: выход $it через ${SystemClock.elapsedRealtime() - openedAt} мс от открытия") }
                }
                launch {
                    // Первая плашка после свежего «измеряю» — замер открытия (≤ 3 с).
                    boards.dropWhile { it.network is NetworkCard.Measured }.first { it.network is NetworkCard.Measured }
                    log("экран: плашка сети через ${SystemClock.elapsedRealtime() - openedAt} мс от открытия, " +
                        "${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} мс от старта процесса")
                }
            }
        }
    }
}

/** Вид карточки сервиса для logcat: без имён и адресов. */
private fun kindOf(s: CardStatus): String = when (s) {
    CardStatus.Unknown -> "Unknown"
    is CardStatus.Unlisted -> "Unlisted"
    CardStatus.Searching -> "Searching"
    is CardStatus.Checking -> "Checking"
    is CardStatus.Works -> "Works"
    is CardStatus.Dead -> "Dead"
    is CardStatus.Found -> "Found"
    is CardStatus.NotFound -> "NotFound"
}
