package podbor.app.ui

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
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
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import arrow.core.Either
import arrow.core.raise.either
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import podbor.app.BuildConfig
import podbor.app.R
import podbor.app.share.bundleOf
import podbor.app.share.readTapes
import podbor.app.share.wordsOf
import podbor.app.share.writeShare
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.inputOf
import podbor.app.sub.subscriptionsOf
import podbor.app.vpn.PodborVpnService
import podbor.app.net.DnsFixes
import podbor.core.networkKey
import podbor.core.Board
import podbor.core.CardStatus
import podbor.core.NetworkCard
import podbor.core.board
import podbor.screen.Ask
import podbor.screen.ExitOrigin
import podbor.screen.MainViewModel
import podbor.screen.Page
import podbor.screen.ScanPhase

private const val TAG = "Podbor"
private const val PREFS = "podbor"
private const val ASKED = "permissions_asked"
private const val ALWAYS_ON_HINTED = "always_on_hinted"
private const val PHASE = "scan_phase"

private fun log(msg: String) {
    Log.i(TAG, msg)
}

/**
 * Экран A: сеть сверху (фрагмент, всегда), под ней страница по [MainViewModel.page] — «Добавь подписку»/анализ или
 * главный. Решает ViewModel; здесь — вид и мост к системе: разрешения, согласие на VPN, старт и привязка сервиса,
 * «поделиться», QR. Открытие (холодный старт или возврат из фона) — [MainViewModel.opened].
 */
class MainActivity : FragmentActivity() {
    /** Значения экрана — в общем ViewModel: переживают пересоздание, фрагменты подписаны на них. */
    internal val vm: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val store = SubscriptionStore(filesDir)
                MainViewModel(
                    BuildConfig.VERSION_NAME, BuildConfig.SELF_DISTRIBUTED,
                    PrefsUpdateMemory(getSharedPreferences(PREFS, MODE_PRIVATE)), System::currentTimeMillis,
                    createSavedStateHandle(),
                    { subscriptionsOf(store.load(), System.currentTimeMillis()) },
                )
            }
        }
    }

    /** Метка открытия — для замеров в logcat. */
    private var openedAt = 0L

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        vm.answered()
        // Спрошено — когда ответ получен: диалог, прерванный уходом экрана, спросится снова.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        log("разрешения: " + granted.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
    }

    /** Почему Ленту не отдали (слова под «поделиться Лентой»); null — нечего сказать. */
    private val note = MutableStateFlow<String?>(null)
    internal val shareNote: StateFlow<String?> = note.asStateFlow()

    /** Сканер QR (zxing): текст QR — тем же путём, что буфер. */
    private val qr = registerForActivityResult(ScanContract()) { r ->
        vm.answered()
        r.contents?.let(::input)
    }

    /** Системный выбор «куда отправить». */
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.answered()
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.answered()
        if (r.resultCode == RESULT_OK) {
            startVpn()
        } else {
            // VPN не будет — сервис экрану держать незачем ([MainViewModel.hold]).
            vm.refused()
            log("согласие на VPN не дано")
        }
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vm.attach((binder as PodborVpnService.LocalBinder).service)
        }

        override fun onServiceDisconnected(name: ComponentName) = vm.detach()

        /** Привязка умерла насовсем: отвязаться и привязаться заново — к следующему запуску сервиса. */
        override fun onBindingDied(name: ComponentName) {
            log("экран: привязка к сервису умерла — привязываюсь заново")
            linked.unbind()
            vm.detach()
            linked.bind()
        }
    }

    /** Основная привязка без BIND_AUTO_CREATE: экран не держит сервис живым и не создаёт его сам. */
    private val linked: Binding = Binding(connection, 0)

    /**
     * Держит сервис, пока он нужен экрану без VPN ([MainViewModel.hold]): привязка с BIND_AUTO_CREATE. Сервис ею
     * создаётся без foreground и без VPN; основная привязка ([connection]) подключается к нему.
     */
    private val keeper = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // Основная привязка подключается к созданному сервису сама; это — на случай, если она опоздала.
            if (vm.service.value == null) vm.attach((binder as PodborVpnService.LocalBinder).service)
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    private val kept = Binding(keeper, BIND_AUTO_CREATE)

    /** «назад» — когда ViewModel говорит, что есть куда ([MainViewModel.canGoBack]). */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = vm.back()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тема A всегда тёмная: значки строк состояния и навигации — светлые при любой теме системы.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        openedAt = SystemClock.elapsedRealtime()
        setContentView(R.layout.activity_main)
        // Хозяин шторок-вопросов (геолокация, постоянный VPN): шторка — своё окно поверх страниц, месту в разметке не нужна.
        addContentView(
            ComposeView(this).apply {
                setContent {
                    val ask by vm.ask.collectAsStateWithLifecycle()
                    when (ask) {
                        Ask.Disclosure -> PodborTheme { LocationSheet(onAllow = { disclosed(true) }, onDecline = { disclosed(false) }) }
                        Ask.AlwaysOn -> PodborTheme { AlwaysOnSheet(onOpen = { hinted(open = true) }, onLater = { hinted(open = false) }) }
                        Ask.DnsRepair -> {
                            val fixedNow by dnsFixes.all.collectAsStateWithLifecycle()
                            val here = networkKey(vm.board.value.network.os)
                            PodborTheme { DnsSheet(fixed = here != null && here in fixedNow, onYes = { repairDns(here) }, onNo = { vm.answered() }) }
                        }
                        Ask.Permissions, Ask.VpnConsent, Ask.Chooser, Ask.Qr, null -> Unit
                    }
                }
            },
            ViewGroup.LayoutParams(0, 0),
        )
        onBackPressedDispatcher.addCallback(this, back)
        // Привязка без создания — сразу: к сервису, созданному стартом VPN или анализом, экран подключится сам.
        linked.bind()
        watch(vm.board)
        showPages()
        bridge()
        if (savedInstanceState == null) {
            opening("холодный старт")
            shared(intent)
        } else {
            restore(savedInstanceState.getString(PHASE))
        }
    }

    /** singleTask: «поделиться → Podbor» при открытом экране приходит сюда. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    /** Возврат из открытого вопроса (окно системы, шторка) — не новое открытие. */
    override fun onRestart() {
        super.onRestart()
        if (vm.ask.value == null) opening("возврат")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PHASE, phaseKind(vm.scan.value))
    }

    /** Вид фазы для Bundle: доски и ошибки не сохраняем — они выводятся заново. */
    private fun phaseKind(p: ScanPhase): String = when (p) {
        ScanPhase.Idle -> "idle"
        ScanPhase.Choosing -> "choosing"
        is ScanPhase.Running, is ScanPhase.Done, is ScanPhase.Failed -> "scan"
    }

    /**
     * Пересоздание Activity. ViewModel пережил его — всё на месте. Новый (процесс убит): «Сменить подписку» остаётся
     * выбором; прерванный анализ (или неудачная попытка — она могла погасить VPN) — заново через открытие.
     */
    private fun restore(kind: String?) {
        if (vm.scan.value != ScanPhase.Idle) return
        when (kind) {
            "choosing" -> vm.changeSubscription()
            "scan" -> opening("пересоздание")
        }
    }

    override fun onDestroy() {
        linked.unbind()
        kept.unbind()
        super.onDestroy()
    }

    /** Новое открытие экрана: метка для замера; решает ViewModel, разрешения — один раз. */
    private fun opening(why: String) {
        openedAt = SystemClock.elapsedRealtime()
        log("экран: открыт ($why); от старта процесса ${openedAt - Process.getStartElapsedRealtime()} мс")
        vm.opened(askPermissions = !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ASKED, false))
        vm.hintAlwaysOn(seen = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ALWAYS_ON_HINTED, false))
    }

    /** Ответ в шторке постоянного VPN — больше не спрашиваем; «Открыть настройки» — системный экран VPN. */
    private fun hinted(open: Boolean) {
        if (vm.ask.value != Ask.AlwaysOn) return
        log("шторка постоянного VPN: " + if (open) "открыть настройки" else "не сейчас")
        vm.answered()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ALWAYS_ON_HINTED, true).apply()
        if (open) Either.catch { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
    }

    /** Ответ в шторке геолокации → системный запрос (отказ — без геолокации; уведомления спрашиваются всё равно). */
    private fun disclosed(locationAllowed: Boolean) {
        // Второй ответ той же шторки — не второй запрос разрешений.
        if (vm.ask.value != Ask.Disclosure) return
        val wanted = permissionsFor(locationAllowed, Build.VERSION.SDK_INT)
        log("шторка геолокации: " + if (locationAllowed) "разрешить" else "не сейчас")
        if (wanted.isEmpty()) {
            vm.answered()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        } else {
            vm.asked(Ask.Permissions)
            permissions.launch(wanted.toTypedArray())
        }
    }

    /**
     * Страница под сетью — по [MainViewModel.page]: смена вида страницы — транзакция FragmentManager (без back stack;
     * «назад» — [back]). Пока экран виден: транзакции после onSaveInstanceState запрещены, а вернувшись, экран догонит
     * последнюю.
     */
    private fun showPages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.page.filterNotNull().collect { page ->
                    val kind = when (page) {
                        Page.Main -> MainFragment::class.java
                        Page.AddSubscription, is Page.Scanning -> SubscriptionFragment::class.java
                    }
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

    /**
     * Мост к системе: сервис держится, пока он нужен без VPN (и в фоне — анализ идёт); старт VPN выдаётся, когда
     * ViewModel его ждёт, — только при видимом экране: согласие — окно системы.
     */
    private fun bridge() {
        lifecycleScope.launch { vm.hold.collect { if (it) kept.bind() else kept.unbind() } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.canGoBack.collect { back.isEnabled = it } }
                launch { vm.needsStart.collect { if (it) ensureVpn() } }
            }
        }
    }

    /** Текст подписки (буфер, QR, «поделиться»): не принялся — словами на странице подписки, иначе — анализ. */
    internal fun input(raw: String) {
        log("подписка: вход на анализ")
        vm.input(inputOf(raw))
    }

    internal fun finishScan() = vm.finishScan()

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

    /** «⋮» → «Поддержка провайдера»: ссылка провайдера — браузером или его приложением; открыть нечем — молча. */
    internal fun openSupport(url: String) {
        log("экран: поддержка провайдера")
        Either.catch { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    internal fun changeSubscription() = vm.changeSubscription()

    /** Кнопка «QR»: сканер zxing; возврат из него — не новое открытие. */
    internal fun scanQr() {
        vm.asked(Ask.Qr)
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
                vm.asked(Ask.VpnConsent)
                consent.launch(ask)
            }
        }
    }

    private fun startVpn() {
        // Сначала старт: дальше сервис держит он, и отпущенная после него привязка ([MainViewModel.hold]) его не гасит.
        startForegroundService(Intent(this, PodborVpnService::class.java).setAction(PodborVpnService.ACTION_START))
        vm.issued()
        linked.bind()
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
        log("экран: нажатие")
        vm.press()
    }

    /** Ремонт DNS по сетям: что починено — плашке «Сеть» и шторке. */
    internal val dnsFixes by lazy { DnsFixes(this) }

    /** 🔧 на плашке «Сеть»: шторка ремонта DNS. */
    internal fun askDns() {
        log("экран: ремонт DNS — шторка")
        vm.asked(Ask.DnsRepair)
    }

    /**
     * Ответ «Починить» / «Вернуть DNS сети» в шторке: ремонт этой сети переключается, VPN переподнимается тем же путём,
     * что «выключить» и «включить» — Xray стартует с новым DNS для российских доменов. Сети нет — чинить нечего.
     */
    private fun repairDns(network: String?) {
        if (vm.ask.value != Ask.DnsRepair) return
        vm.answered()
        if (network == null) return
        val on = !dnsFixes.fixed(network)
        log("ремонт DNS: " + if (on) "Яндекс DNS на этой сети" else "вернул DNS сети")
        dnsFixes.set(network, on)
        turnOff()
        turnOn()
    }

    /** История строки [label] для шторки сервиса. */
    internal suspend fun history(label: String) = vm.history(label)

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
        vm.asked(Ask.Chooser)
        chooser.launch(Intent.createChooser(send, "Поделиться Лентой"))
    }

    /** Привязка к сервису с [flags]: держится до [unbind]; повторная — ничего. */
    private inner class Binding(private val conn: ServiceConnection, private val flags: Int) {
        private var held = false

        fun bind() {
            if (!held) held = bindService(Intent(this@MainActivity, PodborVpnService::class.java), conn, flags)
        }

        fun unbind() {
            if (held) unbindService(conn)
            held = false
        }
    }

    /** Пока экран виден: метки в logcat — без имён и адресов (смена кнопки, плашка сети после открытия). */
    private fun watch(boards: Flow<Board>) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    boards.map { it.button }.distinctUntilChanged().collect { log("экран: кнопка $it") }
                }
                launch {
                    // Ярус «текущий VPN» — только вид состояния и откуда выход по сервисам, без имён: метка для замера на телефоне.
                    combine(boards, vm.origin) { b, origin ->
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
