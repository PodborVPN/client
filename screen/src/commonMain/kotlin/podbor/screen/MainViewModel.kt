package podbor.screen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import podbor.core.Board
import podbor.core.HARDCODED_SERVICES
import podbor.core.PulseView
import podbor.core.ServiceId
import podbor.core.board

/**
 * Главный экран без вьюх: значения, на которые подписаны вьюхи (Compose на Android, SwiftUI на iOS), и намерения
 * человека. Переживает пересоздание экрана; сервис приходит и уходит ([attach]/[detach]) — значения остаются последними.
 */
class MainViewModel(
    private val version: String,
    private val selfDistributed: Boolean,
    private val memory: UpdateMemory,
    private val nowMs: () -> Long,
    // Переживает смерть процесса: открытый вопрос и отказ в согласии.
    private val saved: SavedStateHandle,
    private val shelf: SubscriptionShelf,
    // Область — как у viewModelScope (Main, гаснет с ViewModel); тесты дают свою, на часах теста.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel(scope) {
    /** Подключённый сервис; нет — null (ещё не привязан или ушёл после стопа). */
    val service: StateFlow<ServicePort?>
        field = MutableStateFlow<ServicePort?>(null)

    /** Факты экрана о VPN: свой последний шаг, привязка, последнее слово сессии. */
    val facts: StateFlow<LinkFacts>
        field = MutableStateFlow(
            when {
                saved.get<Boolean>(REFUSED) == true -> LinkFacts().refused()
                saved.get<String>(ASK) == Ask.VpnConsent.name -> LinkFacts().askingConsent()
                else -> LinkFacts()
            },
        )

    /** Открытый вопрос человеку; null — ничего не спрошено. */
    val ask: StateFlow<Ask?>
        field = MutableStateFlow(saved.get<String>(ASK)?.let { name -> Ask.entries.firstOrNull { it.name == name } })

    /** Сохранена ли подписка; null — ещё не прочитали (страницы нет). */
    val subscribed: StateFlow<Boolean?>
        field = MutableStateFlow<Boolean?>(null)

    /** Что показать о подписке в «⋮»: пересчитывается, когда подписка прочитана заново. */
    val subscriptionNote: StateFlow<SubscriptionNote?>
        field = MutableStateFlow<SubscriptionNote?>(null)

    val scan: StateFlow<ScanPhase>
        field = MutableStateFlow<ScanPhase>(ScanPhase.Idle)

    /** Шторка о ссылках подписки, не ставших серверами (часть или все); null — нечего сказать или «Понятно». */
    val skipped: StateFlow<Skipped?>
        field = MutableStateFlow<Skipped?>(null)

    /** Страница под сетью; null — подписка ещё не прочитана. */
    val page: StateFlow<Page?> =
        combine(subscribed, scan) { has, p -> has?.let { pageOf(it, p) } }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** «назад» есть: на анализе (прервать его) и на странице подписки при сохранённой (к главному). */
    val canGoBack: StateFlow<Boolean> = combine(page, subscribed) { p, has ->
        when (p) {
            is Page.Scanning -> true
            Page.AddSubscription -> has == true
            Page.Main, null -> false
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Чего ждём от сервиса после последнего открытия. */
    val want: StateFlow<Want>
        field = MutableStateFlow<Want>(Want.Idle)

    /** Открытие, для которого старт VPN уже выдан или согласие уже спрошено: второй раз не выдаём. */
    private val started = MutableStateFlow<Long?>(null)

    /**
     * Платформе пора выдать старт VPN (спросить согласие или стартовать): главный, открытие ждёт сессии, старт для него
     * ещё не выдан и вопросов нет — шторка разрешений идёт первой.
     */
    val needsStart: StateFlow<Boolean> = combine(want, started, page, ask) { w, done, p, a ->
        w is Want.Open && done != w.atMs && p == Page.Main && a == null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Сервис нужен экрану и без VPN: анализ и плашка сети (страница подписки) или старт ещё впереди. Дальше сервис
     * держит его собственный старт.
     */
    val hold: StateFlow<Boolean> = combine(page, needsStart) { p, start -> (p != null && p != Page.Main) || start }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Запрос анализа: номер — чтобы тот же текст после итога анализировался заново; null — «ОК», выход. */
    private val analysis = MutableStateFlow<Analysis?>(null)
    private var analyses = 0

    val board: StateFlow<Board> =
        service.keepLast { it.board }.stateIn(viewModelScope, SharingStarted.Eagerly, board(HARDCODED_SERVICES, emptyMap()))

    val origin: StateFlow<Map<ServiceId, ExitOrigin>> =
        service.keepLast { it.exitOrigin }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Пульс — только пока экран смотрит (и [PULSE_STOP_MS] после: поворот экрана не роняет опрос в редкий): подписка
     * экрана и есть «смотрит» для опроса сервиса. Без сервиса — пусто, а не последний: величины без сессии врут.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pulse: StateFlow<PulseView> =
        service.flatMapLatest { it?.pulse ?: flowOf(PulseView.Empty) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(PULSE_STOP_MS), PulseView.Empty)

    /**
     * Чип «обновить до …»: сразу — по последнему известному релизу; GitHub спрашивается, только пока экран подписан
     * (виден): при каждом открытии, подключении к сервису и смене сессии (VPN поднялся — туннель для белых списков).
     * Запрос — веб-редирект GitHub без лимита, тормоз по времени не нужен. Сборка не с GitHub — null.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val update: StateFlow<Offer?> =
        if (!selfDistributed) {
            MutableStateFlow(null)
        } else {
            service.keepLast { s -> s.session.map { s } }
                .transformLatest { s ->
                    s.latestRelease()?.let { got ->
                        memory.remember(got)
                        emit(offerOf(version, got))
                    }
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), offerOf(version, memory.known()))
        }

    init {
        // Факты — всю жизнь экрана, и пока он в фоне: отзыв VPN из Настроек иначе никто бы не увидел.
        viewModelScope.launch { service.keepLast { it.session }.collect { s -> fact { it.seen(s) } } }
        viewModelScope.launch { reload() }
        viewModelScope.launch {
            want.collectLatest { w ->
                when (w) {
                    Want.Idle -> Unit
                    is Want.Open -> open(w)
                    is Want.Line -> line(w)
                }
            }
        }
        viewModelScope.launch {
            analysis.collectLatest { a -> if (a != null) analyze(a.input) else finish() }
        }
    }

    /**
     * Экран открыт (холодный старт, возврат, «включить»): подписка заново; дальше — по странице: на главном — сессия и
     * замер на ней, на странице подписки — плашка сети без VPN, на анализе — ничего. [askPermissions] — сначала шторка.
     */
    fun opened(askPermissions: Boolean) {
        viewModelScope.launch {
            val at = nowMs()
            val p = pageOf(reload(), scan.value)
            if (p is Page.Scanning) return@launch
            if (askPermissions) asked(Ask.Disclosure)
            want.value = if (p == Page.Main) Want.Open(at) else Want.Line(at)
        }
    }

    /**
     * Замер открытия, как только есть сервис; новый сервис — заново. Замера не было (быстрое выкл→вкл, неудачный подъём)
     * — ждём следующего подъёма.
     */
    private suspend fun open(w: Want.Open) = service.collectLatest { s ->
        if (s == null) return@collectLatest
        while (true) {
            val measured = s.open()
            s.refreshSubscription()
            reload()
            if (measured) {
                want.compareAndSet(w, Want.Idle)
                return@collectLatest
            }
            s.session.dropWhile { it == SessionView.Up }.first { it == SessionView.Up }
        }
    }

    /** Плашка сети без VPN: раз на открытие, когда сервис есть и вопросов нет (ответ на них — не новое открытие). */
    private suspend fun line(w: Want.Line) {
        val s = combine(service, ask) { s, a -> s.takeIf { a == null } }.filterNotNull().first()
        s.measureLine()
        want.compareAndSet(w, Want.Idle)
    }

    /** Текст подписки разобран платформой: не принялся — словами; иначе анализ. Посреди анализа — ничего. */
    fun input(parsed: Either<InputError, SubscriptionInput>) {
        if (scan.value is ScanPhase.Running) return
        skipped.value = null
        when (parsed) {
            is Either.Left -> scan.value = ScanPhase.Failed(parsed.value)
            is Either.Right -> {
                scan.value = ScanPhase.Running(null)
                analysis.value = Analysis(parsed.value, ++analyses)
            }
        }
    }

    /** Анализ в сервисе (VPN гаснет на его время): доски по ходу — в [scan], пропущенные ссылки — в [skipped]. */
    private suspend fun analyze(input: SubscriptionInput) {
        val s = service.filterNotNull().first()
        val out = s.analyze(input, onSkipped = { skipped.value = it }) { b ->
            scan.update { if (it is ScanPhase.Running) ScanPhase.Running(b) else it }
        }
        reload()
        (out.leftOrNull() as? InputError.NoServers)?.let { skipped.value = it.skipped }
        scan.value = when (out) {
            is Either.Left -> ScanPhase.Failed(out.value)
            is Either.Right -> ScanPhase.Done(out.value)
        }
    }

    /** «Понятно» на шторке пропущенных ссылок. */
    fun dismissSkipped() {
        skipped.value = null
    }

    /**
     * «ОК» (или «назад» на анализе): идущий анализ прерывается — драйвер сохраняет частичный паспорт. Повторное «ОК» —
     * то же значение, ничего не делает.
     */
    fun finishScan() {
        analysis.value = null
    }

    /** Выход с анализа: подписка сохранена — главный и открытие, не успела — снова «Добавь подписку». */
    private suspend fun finish() {
        if (scan.value !is ScanPhase.Running && scan.value !is ScanPhase.Done) return
        val has = reload()
        scan.value = ScanPhase.Idle
        if (has) want.value = Want.Open(nowMs())
    }

    /** «⋮» → «Сменить подписку». VPN не трогаем: его погасит анализ новой подписки, если до него дойдёт. */
    fun changeSubscription() {
        scan.value = ScanPhase.Choosing
    }

    /**
     * «назад»: на анализе — прервать его; на странице подписки при сохранённой — к главному. Попытка могла погасить
     * VPN — открытие его поднимет; иначе VPN как был.
     */
    fun back() {
        when (page.value) {
            is Page.Scanning -> finishScan()
            Page.AddSubscription -> if (subscribed.value == true) {
                val tried = scan.value is ScanPhase.Failed
                scan.value = ScanPhase.Idle
                if (tried) want.value = Want.Open(nowMs())
            }
            Page.Main, null -> Unit
        }
    }

    fun press() {
        val s = service.value ?: return
        viewModelScope.launch { s.press() }
    }

    /** Подписка прочитана заново: есть ли она; заметка для «⋮» — по ней же. */
    private suspend fun reload(): Boolean {
        val note = shelf.note()
        subscriptionNote.value = note
        subscribed.value = note != null
        return note != null
    }

    fun attach(port: ServicePort) {
        fact { it.bound() }
        service.value = port
    }

    fun detach() {
        fact { it.unbound() }
        service.value = null
    }

    /** Спросил человека (вопрос сменяет прежний: шторка → системный запрос). Согласие на VPN — ждём его. */
    fun asked(a: Ask) {
        ask.value = a
        saved[ASK] = a.name
        if (a == Ask.VpnConsent) {
            startedFor()
            fact { it.askingConsent() }
        }
    }

    /** Ждём момента для подсказки Always-on; одно ожидание на ViewModel — повторное открытие его не множит. */
    private var hint: Job? = null

    /**
     * Подсказка постоянного VPN — раз за установку ([seen] — уже показывали): шторка, как только VPN заработал и
     * момент подходит ([alwaysOnHintDue]). Сервис ушёл и вернулся — ждём на новом; спросили — ожидание кончено.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun hintAlwaysOn(seen: Boolean) {
        if (seen || hint != null) return
        hint = viewModelScope.launch {
            service.filterNotNull()
                .flatMapLatest { s -> combine(s.session, s.board, ask) { session, b, a -> alwaysOnHintDue(s.alwaysOn(), session, b.button, a) } }
                .first { it }
            asked(Ask.AlwaysOn)
        }
    }

    fun answered() {
        ask.value = null
        saved[ASK] = null
    }

    fun refused() = fact { it.refused() }

    fun issued() {
        startedFor()
        fact { it.issued() }
    }

    private fun startedFor() {
        started.value = (want.value as? Want.Open)?.atMs
    }

    /** Отказ сохраняется вместе с фактами: восстановленный экран не предложит согласие снова сам. */
    private fun fact(f: (LinkFacts) -> LinkFacts) {
        facts.update(f)
        saved[REFUSED] = facts.value.start == Start.Refused
    }
}

private data class Analysis(val input: SubscriptionInput, val n: Int)

/** Сколько пульс держит подписку на сервис после ухода экрана: поворот — не уход. */
const val PULSE_STOP_MS: Long = 5_000

private const val ASK = "ask"
private const val REFUSED = "vpn_refused"
