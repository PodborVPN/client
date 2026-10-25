package podbor.app.vpn

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import podbor.app.sub.Fetched
import podbor.screen.InputError
import podbor.screen.Skipped
import podbor.screen.skippedOf
import podbor.app.sub.Subscription
import podbor.screen.SubscriptionInput
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.fetchText
import podbor.app.sub.fetched
import podbor.app.sub.refreshDue
import podbor.app.sub.chosen
import podbor.app.sub.current
import podbor.app.sub.newSubscriptionId
import podbor.app.sub.put
import podbor.app.sub.termOf
import podbor.app.sub.updated
import podbor.app.sub.without
import podbor.core.keptOnly
import podbor.driver.Store
import podbor.screen.Analyzed
import podbor.screen.Picked
import podbor.screen.Support
import podbor.screen.Term
import podbor.screen.LATEST_RELEASE_PAGE
import podbor.screen.apkUrlOf
import podbor.screen.releasePageOf
import podbor.screen.Release
import podbor.screen.tagOf
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.GENERAL_FOLLOWS
import podbor.core.HARDCODED_SERVICES
import podbor.core.OsNetwork
import podbor.core.Passport
import podbor.core.Prior
import podbor.core.ScanBoard
import podbor.core.Scenario
import podbor.core.Server
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.WhiteHint
import podbor.core.WhiteLists
import podbor.core.board
import podbor.core.scenarioOf
import podbor.core.sniWhite
import podbor.core.whiteHint
import podbor.driver.Clock
import podbor.driver.serverOrNull
import podbor.driver.Driver
import podbor.driver.EngineError
import podbor.driver.FRAME_MS
import podbor.driver.Opening
import podbor.driver.PassportStore
import podbor.driver.Warm
import podbor.driver.paced
import podbor.screen.ExitOrigin

/** Замер открытия вытеснен нажатием (не стоп сессии). */
private class Superseded : CancellationException("замер вытеснен нажатием")

/** Поднятая сессия: прогретый Xray и его водитель (выходы балансеров сессии — [Driver.exits]). */
class Live(val driver: Driver, val warm: Warm)

/**
 * Xray один на процесс: сессия VPN держит [xray] всю свою жизнь (от подъёма до освобождения области), анализ — на время
 * своего прогона. Замок встаёт первым ресурсом области — отпускается последним, после остановки Xray. Ждать замок —
 * отменяемо (не `install`: его acquire NonCancellable), стоп сессии, ждущей за анализом, срабатывает сразу; между
 * взятием и `onRelease` приостановок нет — замок не утечёт.
 */
suspend fun ResourceScope.holdXray(xray: Mutex) {
    xray.lock()
    onRelease { xray.unlock() }
}

/** Что анализу подписки нужно от сервиса: Android и Xray — снаружи, порядок — в [Desk.analyze]. */
class ScanPorts(
    /** Ссылка → ответ сервера подписки по нижележащей сети; null — не скачалась. */
    val download: suspend (String) -> Fetched?,
    /** То же через SOCKS сервера на устройстве (порт) — туннелем. */
    val downloadVia: suspend (String, Int) -> Fetched? = { _, _ -> null },
    /** Ссылка → ответ мимо провайдера (прокси Яндекса): без заголовков устройства; по умолчанию — как [download]. */
    val proxy: suspend (String) -> Fetched? = download,
    /** Куда редиректит ссылка (Location ответа 3xx): порт SOCKS — туннелем, null — нижележащей сетью; не вышло — null. */
    val location: suspend (String, Int?) -> String? = { _, _ -> null },
    /** Ключи (`Server.key`) поддерживаемых ссылок текста по порядку, с повторами (конвертер, без Xray): разных — серверы. */
    val serverKeys: suspend (String) -> List<String>,
    /** Xray анализа без TUN — в области вызова: её освобождение (конец, отмена) его останавливает. */
    val warm: suspend ResourceScope.(String) -> Either<EngineError, Live>,
    /** Списки белых IP/SNI; null — недоступны (анализ: подсказки — Unlikely; нажатие: подсказки — из паспорта). */
    val lists: suspend () -> WhiteLists?,
    val subscriptions: SubscriptionStore,
    val passports: PassportStore,
    /** Память пар сети: при удалении подписки из неё уходят серверы, которых больше нет ни в одной. */
    val memory: Store,
    /** Подписка удалена, забыто серверов (число): её итог трафика и строка Ленты — у сервиса. */
    val forgot: (Subscription, Int) -> Unit = { _, _ -> },
    /** Стоп сессии VPN той же дорогой, что лепесток «выкл». */
    val stopSession: () -> Unit,
    /** Часы обновления подписки: когда скачана, пора ли снова. */
    val clock: Clock = Clock { System.currentTimeMillis() },
)

/**
 * Открытие и нажатие без Android: сводный экран ядра, который видит Activity, и порядок действий на сессии [host].
 * Открытие и нажатие — по одному ([actions]); раунды линии последнего открытия ([prior]) отдаются нажатию.
 * [detect] — линия мимо туннеля, без Xray (`detectLine` драйвера): плашка сети не ждёт подъёма сессии.
 * Трубопроводы — по [services]: карточка и выход у каждого свои.
 * Анализ подписки ([analyze]) — под замком [xray], который держит и сессия ([holdXray]): пока идёт анализ, сессии нет,
 * значит, нет и открытия/нажатия (они — действия поднятой сессии) — Remember анализа не встретится с успехом нажатия.
 */
class Desk(
    private val host: SessionHost<Live>,
    private val os: () -> OsNetwork,
    private val detect: suspend (OsNetwork, (Board) -> Unit) -> Opening,
    private val services: Set<Service> = HARDCODED_SERVICES,
    private val xray: Mutex,
    private val scan: ScanPorts,
    /** Пул анализа и обновления подписки; тест подставляет свой диспетчер. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val actions = Mutex()

    /** Анализы — по одному: тогда [xray] помимо идущего анализа держит только сессия. */
    private val scans = Mutex()
    /** Пишут открытие и нажатие под [actions]; анализ читает вне замка — отсюда @Volatile. */
    @Volatile
    private var prior: Prior = Prior.None

    /**
     * Подсказки нажатию по спискам белых — раз на прогретый Xray: ~30 тыс. CIDR × серверы — десятки мс, не к
     * каждому нажатию. Пишет и читает нажатие под [actions].
     */
    private var pressHints: Pair<Warm, Map<String, WhiteHint>>? = null

    /** Идущий замер открытия: нажатие его вытесняет. */
    private val openRun = AtomicReference<Job?>(null)

    /** Нажатие ждёт или идёт: следующее не встаёт в очередь. */
    private val pressing = AtomicBoolean(false)

    private val initial = board(services, emptyMap())
    private val boardState = MutableStateFlow(initial)
    val board: StateFlow<Board> = boardState.asStateFlow()

    /** Чьи выходы в идущем замере открытия восстановлены из памяти; кого нет — выбран. */
    private val originState = MutableStateFlow<Map<ServiceId, ExitOrigin>>(emptyMap())
    val origin: StateFlow<Map<ServiceId, ExitOrigin>> = originState.asStateFlow()

    /**
     * Экран открыт. Сессия ещё не поднята — линия меряется сразу, параллельно подъёму, и плашка публикуется по её
     * готовности. После подъёма — проба выхода каждого сервиса с этими раундами (линия заново не меряется). Сервис без
     * своего выхода в сессии получает последний рабочий для этой сети из памяти — он выставляется в Xray и проверяется
     * тем же замером; памяти нет — балансер сервиса за общим. Никакого перебора: один выход из памяти на сервис.
     * Действие — ребёнок сессии: стоп посреди отменяет его (и восстановление) раньше остановки Xray; тогда экран
     * сбрасывается в исходный. `false` — замера не было (сессии нет или её остановили посреди).
     */
    suspend fun open(): Boolean = coroutineScope {
        val net = os()
        val early: Deferred<Opening>? = when (host.state.value) {
            is SessionState.Up -> null
            SessionState.Down, SessionState.Starting, is SessionState.Failed -> async { detect(net) { boardState.value = it } }
        }
        val line = when (host.state.first { it is SessionState.Up || it is SessionState.Failed }) {
            is SessionState.Up -> early?.await()?.prior ?: Prior.None
            SessionState.Down, SessionState.Starting, is SessionState.Failed -> {
                early?.cancel()
                Prior.None
            }
        }
        settle(
            host.act { live ->
                coroutineScope {
                    // Замер — отменяемый ребёнок действия: нажатие его вытесняет, не трогая сессию (open → true).
                    val run = launch(start = CoroutineStart.LAZY) { actions.withLock { measure(live, net, line) } }
                    openRun.set(run)
                    run.start()
                    run.join()
                    openRun.compareAndSet(run, null)
                    Unit
                }
            },
        )
    }

    /** Замер открытия под [actions]: линия при нужде, восстановление, проба выходов. */
    private suspend fun measure(live: Live, net: OsNetwork, line: Prior) {
        // Без своего выхода в сессии — кандидаты на восстановление из памяти.
        val bare = services.filterNot { live.driver.exits.pinned(it.id) }.toSet()
        // Чьи выходы могут стоять непроверенными, если замер вытеснят: пока восстановление идёт — все кандидаты
        // (драйвер закрепляет их по одному, и Xray мог принять цель до отмены), после — вернувшиеся из памяти.
        var unchecked: List<ServiceId> = bare.map { it.id }.sortedBy { it.id }
        try {
            val rounds = when {
                bare.isEmpty() -> line
                else -> {
                    // Линии до подъёма не было или она устарела (повтор открытия на поднятой сессии, поздняя привязка):
                    // линия — здесь, в сессии, чтобы знать сеть для памяти.
                    val known = lineFor(live, net, line)
                    recall(live, bare, net, known)
                    unchecked = recalled()
                    known
                }
            }
            // Раунды — нажатию сразу: если оно вытеснит замер, линию заново не меряет.
            prior = rounds
            val opening = live.driver.open(live.warm, services, net, rounds) { boardState.value = it }
            prior = opening.prior
            dropDead(live, opening.board)
            originState.value = emptyMap()
        } catch (e: Superseded) {
            // Восстановленные выходы не успели проверить — не оставляем их: выходы решит подбор нажатия.
            // Ещё под мьютексом: нажатие начнётся после снятия, значит, своих выходов оно поставить не успело.
            // Снятие незакреплённого безвредно: балансер и так за общим.
            withContext(NonCancellable) {
                unchecked.forEach { live.driver.dropExit(it) }
                originState.value = emptyMap()
            }
            throw e
        }
    }

    /**
     * Нажатие: подбор выходов всех сервисов; раунды линии последнего открытия — подсказка нажатию. Ребёнок сессии, как
     * [open]. Идущий замер открытия вытесняется (это только замер), «ищу» — сразу. Нажатие, пока другое ждёт или идёт,
     * — не в очередь: подбор один.
     */
    suspend fun press() {
        if (!pressing.compareAndSet(false, true)) return
        try {
            openRun.get()?.cancel(Superseded())
            searching()
            settle(
                host.act { live ->
                    actions.withLock {
                        // Вытесненный замер мог успеть опубликовать экран, пока снимался.
                        searching()
                        val hints = pressHintsOf(live.warm) ?: hintsOf(scan.passports.load())
                        live.driver.press(live.warm, services, os(), prior, hints) { boardState.value = it }
                        prior = Prior.None
                    }
                },
            )
        } finally {
            pressing.set(false)
        }
    }

    /**
     * Замер линии без сессии — для плашки сети на странице подписки, где VPN ещё нет: линия мимо туннеля, плашка — по
     * готовности. Поднятой сессии он не нужен (сеть меряет открытие).
     */
    suspend fun measureLine() {
        if (host.state.value is SessionState.Up) return
        detect(os()) { boardState.value = it }
    }

    /** Текущая подписка есть: экран решает, звать ли «Добавь подписку». */
    suspend fun hasSubscription(): Boolean = scan.subscriptions.current() != null

    /**
     * Анализ подписки (на пуле IO; [onBoard] — оттуда же, не чаще раза в кадр — [paced]). Сначала — вход, при живом VPN:
     * текст подписки (ссылка — по нижележащей сети; не скачалась — белые списки по замеру линии сейчас, мимо туннеля) и
     * серверы в нём (конвертер libXray — разбор текста, без Xray: работающей сессии не касается); ссылки, не ставшие
     * серверами, — счётом в [onSkipped] до анализа (ни одной — в NoServers). Вход не годится —
     * ошибка, VPN и прежняя подписка как были. Годится — сессия VPN гасится, пока замок [xray] не наш ([evictSession]);
     * анализы — по одному, старт VPN посреди ждёт конца. Под замком: свой Xray без TUN в своей области — поднялся,
     * подписка сохраняется (не поднялся — ScanFailed, прежняя цела) — подсказки белых списков по IP входа, SNI отдельно
     * — анализ драйвера. Отмена освобождает область (Xray анализа остановлен), частичный паспорт драйвер сохраняет сам.
     * VPN после анализа поднимает экран.
     */
    suspend fun analyze(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard> =
        withContext(io) { scans.withLock { analyzeLocked(input, onSkipped, onBoard) } }

    private suspend fun analyzeLocked(
        input: SubscriptionInput,
        onSkipped: (Skipped) -> Unit,
        onBoard: (ScanBoard) -> Unit,
    ): Either<InputError, ScanBoard> = either {
        val got = fetchText(input, scan.download, ::whitelistNow, tunnel(), scan.proxy).bind()
        val text = got.body
        val drafted = scan.serverKeys(text)
        val keys = drafted.distinct()
        val skipped = skippedOf(text, keys.size, repeats = drafted.size - keys.size)
        ensure(skipped.servers > 0) { InputError.NoServers(skipped) }
        if (skipped.any) onSkipped(skipped)
        evictSession()
        // Замок взят tryLock в evictSession, приостановок до try нет.
        try {
            resourceScope {
                val live = scan.warm(this, text).mapLeft { InputError.ScanFailed }.bind()
                val link = (input as? SubscriptionInput.Link)?.url
                // С подъёма Xray анализа подписка в списке и текущая: человек её вставил — значит, выбрал.
                val id = putCurrent(Subscription(link, text, servers = keys).let { if (link != null) it.fetched(got, scan.clock.nowMs()) else it })
                val lists = scan.lists()
                val hints = hintsBy(lists, live.warm.servers) ?: live.warm.servers.associate { it.key to WhiteHint.Unlikely }
                val sni = lists?.let { l -> live.warm.servers.filter { sniWhite(l, it.sni) }.map { it.key }.toSet() }.orEmpty()
                val last = AtomicReference<ScanBoard?>(null)
                try {
                    paced(FRAME_MS, onBoard) { emit ->
                        live.driver.analyze(live.warm, services, os(), prior, hints, sni) { b -> last.set(b); emit(b) }
                    }.also { mark(id, Analyzed.Done(scan.clock.nowMs())) }
                } catch (e: CancellationException) {
                    // «ОК» или «назад» посреди: отметка — по последней доске; отмену не глотаем.
                    val b = last.get()
                    withContext(NonCancellable) { mark(id, Analyzed.Interrupted(scan.clock.nowMs(), b?.done ?: 0, b?.total ?: keys.size)) }
                    throw e
                }
            }
        } finally {
            xray.unlock()
        }
    }

    /** Подписка [s] — в список и текущая (та же — та же запись); возвращает её id. */
    private suspend fun putCurrent(s: Subscription): String {
        val (shelf, put) = scan.subscriptions.load().put(s, ::newSubscriptionId)
        scan.subscriptions.save(shelf)
        return put.id
    }

    private suspend fun mark(id: String, a: Analyzed) {
        scan.subscriptions.save(scan.subscriptions.load().updated(id) { it.copy(analyzed = a) })
    }

    /** Переанализ сохранённой подписки [id]: вход — её ссылка, без ссылки — текст; дальше как [analyze]. */
    suspend fun reanalyze(id: String, onSkipped: (Skipped) -> Unit, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> {
        val s = scan.subscriptions.load().items.firstOrNull { it.id == id } ?: return Either.Left(InputError.Empty)
        return analyze(s.link?.let { SubscriptionInput.Link(it) } ?: SubscriptionInput.Text(s.text), onSkipped, onBoard)
    }

    /**
     * Выбор подписки [id]: сначала — свежий текст по её ссылке (основная, затем запасная; серверов 0 — как не скачалась:
     * остаёмся на сохранённом). По свежим словам провайдера кончилась — не выбираем ([Picked.Expired]), VPN не трогаем.
     * Иначе — текущая, а сессия на прежней гасится: экран поднимет VPN на новой.
     */
    suspend fun select(id: String): Picked = withContext(io) {
        val picked = scans.withLock {
            val shelf = scan.subscriptions.load()
            val s = shelf.items.firstOrNull { it.id == id } ?: return@withLock Picked.Ready
            val fresh = refreshed(s, prior)
            val picked = pickedOf(termOf(fresh.provider, scan.clock.nowMs()), fresh.provider.support)
            val updated = shelf.updated(id) { fresh }
            scan.subscriptions.save(
                when (picked) {
                    Picked.Ready -> updated.chosen(id)
                    is Picked.Expired -> updated
                },
            )
            picked
        }
        if (picked == Picked.Ready) stopSessionIfAny()
        picked
    }

    /**
     * Удаление подписки [id]: была текущей — сессия на ней гаснет; из паспорта и памяти пар уходят серверы, которых нет
     * ни в одной оставшейся (у переехавшей со старого файла ключей ещё нет — считаем по тексту); итог трафика и строка
     * Ленты — [ScanPorts.forgot].
     */
    suspend fun delete(id: String): Unit = withContext(io) {
        val wasCurrent = scans.withLock {
            val shelf = scan.subscriptions.load()
            val gone = shelf.items.firstOrNull { it.id == id } ?: return@withLock false
            // Сначала — с диска: сессия, поднятая следом за стопом (always-on, выкл→вкл), найдёт «нет подписки».
            val rest = shelf.without(id)
            scan.subscriptions.save(rest)
            val keep = rest.items.flatMap { s -> s.servers.ifEmpty { scan.serverKeys(s.text) } }.toSet()
            val was = scan.passports.load()
            if (was != null) scan.passports.save(was.keptOnly(keep))
            scan.memory.saveMemory(scan.memory.loadMemory().keptOnly(keep))
            scan.forgot(gone, was?.servers?.keys?.count { it !in keep } ?: 0)
            shelf.currentId == id
        }
        if (wasCurrent) stopSessionIfAny()
    }

    /** Сессия поднята или поднимается — гасим и ждём, пока хост исполнит стоп; нет её — ничего. */
    private suspend fun stopSessionIfAny() {
        when (host.state.value) {
            is SessionState.Up, SessionState.Starting -> {
                val done = host.stops.value
                scan.stopSession()
                host.stops.first { it > done }
            }
            SessionState.Down, is SessionState.Failed -> Unit
        }
    }

    /**
     * Свежий текст подписки [s] по её ссылке (основная, затем запасная провайдера); белые списки — по линии [line].
     * Не скачалась, без ссылки или в новом тексте нет серверов — [s] как есть.
     */
    private suspend fun refreshed(s: Subscription, line: Prior): Subscription {
        val link = s.link ?: return s
        val net = os()
        suspend fun fetch(url: String) = fetchText(SubscriptionInput.Link(url), scan.download, { whiteBy(net, line) }, tunnel(), scan.proxy)
        val got = fetch(link).getOrNull() ?: s.fallbackUrl?.let { fetch(it).getOrNull() } ?: return s
        val keys = scan.serverKeys(got.body).distinct()
        if (keys.isEmpty()) return s
        return s.fetched(got, scan.clock.nowMs()).copy(servers = keys)
    }

    private fun pickedOf(t: Term, support: Support): Picked = when (t) {
        is Term.Expired, Term.OutOfTraffic -> Picked.Expired(support)
        Term.Unknown, is Term.Until -> Picked.Ready
    }

    /**
     * Берёт [xray], гася сессию, пока замок не наш: сессия, стартовавшая между стопом и замком (старт экрана,
     * always-on, быстрое выкл→вкл), снова берёт Xray — её снова гасим. Каждый круг ждёт, пока хост исполнит наш стоп
     * ([SessionHost.stops]): область сессии освобождена, замок отпущен. Сессия, стартовавшая после взятия, ждёт анализ.
     */
    private suspend fun evictSession() {
        while (!xray.tryLock()) {
            val done = host.stops.value
            scan.stopSession()
            host.stops.first { it > done }
        }
    }

    /** Подписка не скачалась: белые ли списки — по замеру линии сейчас (мимо туннеля, без Xray). */
    private suspend fun whitelistNow(): Boolean {
        val net = os()
        return whiteBy(net, detect(net) { }.prior)
    }

    /**
     * Скачивание туннелем: SOCKS сервера общего выхода поднятой сессии; сессии или выхода нет — null. Порт
     * берётся сейчас: сессия, погасшая до скачивания, — просто неудачная попытка.
     */
    private fun tunnel(): (suspend (String) -> Fetched?)? = tunnelPort()?.let { port -> { url -> scan.downloadVia(url, port) } }

    private fun tunnelPort(): Int? {
        val live = (host.state.value as? SessionState.Up)?.live ?: return null
        val exit = live.driver.exits.state.value.general.serverOrNull() ?: return null
        return live.driver.socksPort(exit, live.warm)
    }

    /** Линия для памяти на поднятой сессии: свежая есть — она, нет — меряем здесь. */
    private suspend fun lineFor(live: Live, net: OsNetwork, line: Prior): Prior = when (val f = live.driver.fresh(line)) {
        Prior.None -> detect(net) { boardState.value = it }.prior
        is Prior.Detected -> f
    }

    private fun whiteBy(net: OsNetwork, p: Prior): Boolean = when (p) {
        Prior.None -> false
        is Prior.Detected -> white(scenarioOf(net, p.rounds.map { it.line }))
    }

    private fun white(s: Scenario): Boolean = when (s) {
        Scenario.MobileWhitelist, Scenario.SuspectWhitelist -> true
        Scenario.MobileOpen, Scenario.Wifi, Scenario.Offline, Scenario.Unclear -> false
    }

    /**
     * Тихое обновление подписки по сохранённой ссылке — после открытия, отдельно от него: нажатие не ждёт.
     * Не пора ([refreshDue]), ссылки нет, идёт анализ — ничего. Белые списки — по линии последнего открытия ([prior]),
     * своего замера нет. Не скачалась ни основная, ни запасная провайдера, или в новом тексте нет серверов — молча
     * остаёмся на прежнем; провайдер сказал о переезде — ссылка новая ([fetched]). Новый текст сессия
     * берёт при следующем подъёме VPN: идущую не рвём.
     */
    suspend fun refreshSubscription(): Unit = withContext(io) {
        if (!scans.tryLock()) return@withContext
        try {
            val shelf = scan.subscriptions.load()
            val old = shelf.current() ?: return@withContext
            // Переехавшая со старого файла — без ключей серверов: дописываем по тексту (карточка и забвение на них).
            val filled = if (old.servers.isEmpty()) old.copy(servers = scan.serverKeys(old.text)) else old
            // Основная не скачалась никаким путём — запасная ссылка провайдера; ссылка подписки остаётся прежней.
            val next = if (refreshDue(filled, scan.clock.nowMs())) refreshed(filled, prior) else filled
            if (next != old) scan.subscriptions.save(shelf.updated(old.id) { next })
        } finally {
            scans.unlock()
        }
    }

    /**
     * Последний релиз на GitHub: тег — из редиректа [LATEST_RELEASE_PAGE], ссылка — на APK, если он уже в релизе (его
     * ссылка тоже редиректит), иначе на страницу релиза. Каждый запрос — напрямую, не вышло — туннелем найденного выхода
     * (в белых списках GitHub закрыт). Тег не узнали — null, молча: обновлялка не главное.
     */
    suspend fun latestRelease(): Release? = withContext(io) {
        val port = tunnelPort()
        suspend fun locate(url: String): String? = scan.location(url, null) ?: port?.let { scan.location(url, it) }
        val tag = locate(LATEST_RELEASE_PAGE)?.let(::tagOf) ?: return@withContext null
        val apk = apkUrlOf(tag)
        Release(tag, if (locate(apk) != null) apk else releasePageOf(tag))
    }

    private fun searching() {
        boardState.update { b -> b.copy(button = Button.Searching, cards = b.cards.map { it.copy(status = CardStatus.Searching) }) }
    }

    /** Восстановленные выходы [bare] уже приняты Xray (Exits драйвера) — для слов «последний рабочий» помечаются. */
    private suspend fun recall(live: Live, bare: Set<Service>, net: OsNetwork, line: Prior) {
        val back = live.driver.recall(live.warm, bare, net, line)
        originState.value = back.keys.associateWith { ExitOrigin.Recalled }
    }

    /** Сервисы, чьи выходы восстановлены в идущем замере, по ServiceId. */
    private fun recalled(): List<ServiceId> =
        originState.value.filterValues { it == ExitOrigin.Recalled }.keys.sortedBy { it.id }

    /**
     * Восстановленный выход, не прошедший проверку, не держим: «VPN включён, а сервис молчит» хуже выхода, который
     * повторяет общий. Override снимается средствами Xray (балансер сервиса снова за общим; у GENERAL_FOLLOWS — общий
     * выбирает сам, уведомление — без выхода); на экране остаётся Dead с виной и кнопка «Подобрать». Память не пишется.
     * Перед снятием общего незакреплённые сервисы, прошедшие пробу на нём (Works), закрепляются на нём же (спека:
     * выход, прошедший пробу сервиса, закрепляется) — иначе их балансеры ушли бы к выбору Xray, а карточка осталась бы ✓.
     */
    private suspend fun dropDead(live: Live, board: Board) {
        val dead = board.cards.filter { it.status is CardStatus.Dead }.map { it.service }.toSet()
        val drop = recalled().filter { it in dead }
        val general = live.driver.exits.state.value.general.serverOrNull()
        if (GENERAL_FOLLOWS in drop && general != null) {
            board.cards
                .filter { it.status is CardStatus.Works && !live.driver.exits.pinned(it.service) }
                .forEach { live.driver.pinExit(it.service, general) }
        }
        drop.forEach { live.driver.dropExit(it) }
    }

    /** Подсказки по вшитым спискам белых (IP входа) для каждого из [servers]; списков нет — null. */
    private fun hintsBy(lists: WhiteLists?, servers: List<Server>): Map<String, WhiteHint>? =
        lists?.let { l -> servers.associate { it.key to whiteHint(l, it.endpoint.host) } }

    /** Подсказки по спискам для [warm] — из [pressHints], если посчитаны для него же; списков нет — null. */
    private suspend fun pressHintsOf(warm: Warm): Map<String, WhiteHint>? =
        pressHints?.takeIf { it.first === warm }?.second
            ?: hintsBy(scan.lists(), warm.servers)?.also { pressHints = warm to it }

    /** Запасные подсказки нажатию (списков нет) — из паспорта подписки; паспорта нет — пусто (порядок как раньше). */
    private fun hintsOf(passport: Passport?): Map<String, WhiteHint> =
        passport?.servers?.mapValues { it.value.whiteHint }.orEmpty()

    private fun settle(acted: Acted<Unit>): Boolean = when (acted) {
        is Acted.Done -> true
        Acted.NoSession -> {
            boardState.value = initial
            originState.value = emptyMap()
            false
        }
    }
}
