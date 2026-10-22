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
import podbor.app.sub.InputError
import podbor.app.sub.Subscription
import podbor.app.sub.SubscriptionInput
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.fetchText
import podbor.app.sub.fetched
import podbor.app.sub.refreshDue
import podbor.app.update.LATEST_RELEASE_API
import podbor.app.update.Release
import podbor.app.update.releaseOf
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
import podbor.driver.Driver
import podbor.driver.EngineError
import podbor.driver.FRAME_MS
import podbor.driver.Opening
import podbor.driver.PassportStore
import podbor.driver.Warm
import podbor.driver.paced

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
    /** Сколько поддерживаемых серверов в тексте (конвертер, без Xray). */
    val servers: suspend (String) -> Int,
    /** Xray анализа без TUN — в области вызова: её освобождение (конец, отмена) его останавливает. */
    val warm: suspend ResourceScope.(String) -> Either<EngineError, Live>,
    /** Списки белых IP/SNI; null — недоступны (анализ: подсказки — Unlikely; нажатие: подсказки — из паспорта). */
    val lists: suspend () -> WhiteLists?,
    val subscriptions: SubscriptionStore,
    val passports: PassportStore,
    /** Стоп сессии VPN той же дорогой, что лепесток «выкл». */
    val stopSession: () -> Unit,
    /** Часы обновления подписки: когда скачана, пора ли снова. */
    val clock: Clock = Clock { System.currentTimeMillis() },
)

/** Откуда выход сервиса в замере открытия: выбран (нажатием в этой сессии) или восстановлен из памяти сети. */
enum class ExitOrigin { Chosen, Recalled }

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
                    val known = when (val f = live.driver.fresh(line)) {
                        Prior.None -> detect(net) { boardState.value = it }.prior
                        is Prior.Detected -> f
                    }
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

    /** Подписка сохранена: экран решает, звать ли «Добавь подписку». */
    suspend fun hasSubscription(): Boolean = scan.subscriptions.load() != null

    /**
     * Анализ подписки (на пуле IO; [onBoard] — оттуда же, не чаще раза в кадр — [paced]). Сначала — вход, при живом VPN:
     * текст подписки (ссылка — по нижележащей сети; не скачалась — белые списки по замеру линии сейчас, мимо туннеля) и
     * серверы в нём (конвертер libXray — разбор текста, без Xray: работающей сессии не касается). Вход не годится —
     * ошибка, VPN и прежняя подписка как были. Годится — сессия VPN гасится, пока замок [xray] не наш ([evictSession]);
     * анализы — по одному, старт VPN посреди ждёт конца. Под замком: свой Xray без TUN в своей области — поднялся,
     * подписка сохраняется (не поднялся — ScanFailed, прежняя цела) — подсказки белых списков по IP входа, SNI отдельно
     * — анализ драйвера. Отмена освобождает область (Xray анализа остановлен), частичный паспорт драйвер сохраняет сам.
     * VPN после анализа поднимает экран.
     */
    suspend fun analyze(input: SubscriptionInput, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> =
        withContext(io) { scans.withLock { analyzeLocked(input, onBoard) } }

    private suspend fun analyzeLocked(input: SubscriptionInput, onBoard: (ScanBoard) -> Unit): Either<InputError, ScanBoard> = either {
        val got = fetchText(input, scan.download, ::whitelistNow, tunnel()).bind()
        val text = got.body
        ensure(scan.servers(text) > 0) { InputError.NoServers }
        evictSession()
        // Замок взят tryLock в evictSession, приостановок до try нет.
        try {
            resourceScope {
                val live = scan.warm(this, text).mapLeft { InputError.ScanFailed }.bind()
                val link = (input as? SubscriptionInput.Link)?.url
                scan.subscriptions.save(
                    Subscription(link, text).let { if (link != null) it.fetched(got, scan.clock.nowMs()) else it },
                )
                val lists = scan.lists()
                val hints = hintsBy(lists, live.warm.servers) ?: live.warm.servers.associate { it.key to WhiteHint.Unlikely }
                val sni = lists?.let { l -> live.warm.servers.filter { sniWhite(l, it.sni) }.map { it.key }.toSet() }.orEmpty()
                paced(FRAME_MS, onBoard) { emit -> live.driver.analyze(live.warm, services, os(), prior, hints, sni, emit) }
            }
        } finally {
            xray.unlock()
        }
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
    private fun tunnel(): (suspend (String) -> Fetched?)? {
        val live = (host.state.value as? SessionState.Up)?.live ?: return null
        val exit = live.driver.exits.state.value.general ?: return null
        val port = live.driver.socksPort(exit, live.warm) ?: return null
        return { url -> scan.downloadVia(url, port) }
    }

    private fun whiteBy(net: OsNetwork, p: Prior): Boolean = when (p) {
        Prior.None -> false
        is Prior.Detected -> when (scenarioOf(net, p.rounds.map { it.line })) {
            Scenario.MobileWhitelist, Scenario.SuspectWhitelist -> true
            Scenario.MobileOpen, Scenario.Wifi, Scenario.Offline, Scenario.Unclear -> false
        }
    }

    /**
     * Тихое обновление подписки по сохранённой ссылке — после открытия, отдельно от него: нажатие не ждёт.
     * Не пора ([refreshDue]), ссылки нет, идёт анализ — ничего. Белые списки — по линии последнего открытия ([prior]),
     * своего замера нет. Не скачалось или в новом тексте нет серверов — молча остаёмся на прежнем. Новый текст сессия
     * берёт при следующем подъёме VPN: идущую не рвём.
     */
    suspend fun refreshSubscription(): Unit = withContext(io) {
        if (!scans.tryLock()) return@withContext
        try {
            val old = scan.subscriptions.load() ?: return@withContext
            val link = old.link ?: return@withContext
            if (!refreshDue(old, scan.clock.nowMs())) return@withContext
            val net = os()
            val line = prior
            val got = fetchText(SubscriptionInput.Link(link), scan.download, { whiteBy(net, line) }, tunnel()).getOrNull()
                ?: return@withContext
            if (scan.servers(got.body) == 0) return@withContext
            scan.subscriptions.save(old.fetched(got, scan.clock.nowMs()))
        } finally {
            scans.unlock()
        }
    }

    /**
     * Последний релиз на GitHub: напрямую, не вышло — туннелем найденного выхода (в белых списках GitHub закрыт).
     * Не скачался или ответ не разобрать — null, молча: обновлялка не главное.
     */
    suspend fun latestRelease(): Release? = withContext(io) {
        val got = scan.download(LATEST_RELEASE_API) ?: tunnel()?.invoke(LATEST_RELEASE_API)
        got?.body?.let(::releaseOf)
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
        val general = live.driver.exits.state.value.general
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
