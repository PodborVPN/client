package podbor.driver

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.toNonEmptyListOrNull
import arrow.fx.coroutines.ResourceScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import podbor.core.Board
import podbor.core.Deed
import podbor.core.Diagnosis
import podbor.core.Endpoint
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.GENERAL_FOLLOWS
import podbor.core.LINE_FRESH_MS
import podbor.core.Letter
import podbor.core.Memory
import podbor.core.OsNetwork
import podbor.core.Passport
import podbor.core.Prior
import podbor.core.ScanBoard
import podbor.core.ScanFold
import podbor.core.ScanState
import podbor.core.Screen
import podbor.core.Server
import podbor.core.ServerId
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.Vpn
import podbor.core.WhiteHint
import podbor.core.board
import podbor.core.classify as classifyDrafts
import podbor.core.isBlocked
import podbor.core.memoryKey
import podbor.core.merged
import podbor.core.observe
import podbor.core.partialPassport
import podbor.core.render
import podbor.core.renderOpened
import podbor.core.scenario

/** Страховка: нажатие не висит дольше минуты, даже если мир молчит. Срок — на каждый трубопровод. */
private const val PRESS_TIMEOUT_MS = 60_000L

/** Замер при открытии: линия ≤ 2 с и проба выхода с повтором — с запасом. */
private const val OPEN_TIMEOUT_MS = 10_000L

/**
 * Анализ подписки — без срока: идёт, пока не проверит все допустимые серверы; надоело —
 * человек жмёт «ОК, дальше». Срок 150 с обрывал большие бесплатные списки, где много мёртвых, на середине — выглядело
 * как зависание. Каждая проба по-прежнему со своим сроком.
 */
const val SCAN_TIMEOUT_MS = Long.MAX_VALUE

/** Порядок сервисов в прогонах: общий выход (GENERAL_FOLLOWS) первым, прочие — по id. */
private val SERVICE_ORDER = compareBy<Service>({ it.id != GENERAL_FOLLOWS }, { it.id.id })

/** Сводный экран при открытии и раунды линии, которые можно отдать следующему нажатию. */
data class Opening(val board: Board, val prior: Prior)

/** Прогретая сессия: Xray поднят в той области ресурсов, где получен Warm; серверы с определённым входом. */
data class Warm(val servers: List<Server>)

/**
 * Край ядра: прогрев, нажатие и открытие — трубопровод на каждый сервис, каждый своим актором (owned state)
 * и своим прогоном Ленты. Общие у трубопроводов — линия ([LineHub], одна на действие), выходы балансеров
 * ([exits]), потолок проб на адрес ([gate]) и хранилище памяти (запись под одним замком: слияние, не затирание).
 * Выходы — набора [ConfigOpts.services]: тот же набор, по которому собран конфиг Xray.
 */
class Driver(
    private val converter: LinkConverter,
    private val engine: XrayEngine,
    private val api: XrayApi,
    private val prober: Prober,
    /** Внешний IP выхода через кандидата — по своему эталону (анализ подписки). */
    private val exitProbe: ExitProbe,
    private val gate: EndpointGate,
    private val resolver: Resolver,
    private val line: LineProbe,
    private val clock: Clock,
    private val store: Store,
    private val passportStore: PassportStore,
    private val tape: TapeSink,
    private val opts: ConfigOpts,
    /** Страна IP по локальному geoip ([GeoCountry.of]); null — нет файла или IP вне диапазонов. */
    private val countryOf: (String) -> String? = { null },
    private val pressTimeoutMs: Long = PRESS_TIMEOUT_MS,
    private val scanTimeoutMs: Long = SCAN_TIMEOUT_MS,
) {
    /** Куда указывают балансеры; трубопроводы читают отсюда свой текущий выход и сюда выставляют находку. */
    val exits = Exits(api, opts.services, exitTagOf(opts))

    /** SOCKS на устройстве, через который трафик идёт сервером [server] прогретого [warm]; сервера там нет — null. */
    fun socksPort(server: ServerId, warm: Warm): Int? =
        if (warm.servers.any { it.id == server }) socksPortOf(server, warm.servers, opts) else null

    /** Память пишут трубопроводы и анализ параллельно: загрузка-слияние-запись — по одному. */
    private val memoryLock = Mutex()

    /**
     * Прогрев: подписка → Xray → вход каждого сервера по правилу geoip:ru самого Xray.
     * Xray живёт, пока жива область ресурсов, где вызван прогрев: остановка движка — её освобождение.
     */
    suspend fun ResourceScope.warmUp(subscription: String): Either<EngineError, Warm> = either {
        val outbounds = converter.convert(subscription).bind()
        val unplaced = classifyDrafts(draftsOf(outbounds)) { Entry.Unknown }
        with(engine) { run(buildConfig(unplaced, retag(unplaced, outbounds), opts)) }.bind()
        Warm(coroutineScope { unplaced.map { async { locate(it) } }.awaitAll() })
    }

    /**
     * Одно нажатие на прогретой сессии: трубопроводы [services] параллельно, каждый — буквы ↔ дела до Done или своего
     * срока. Текущий выход трубопровода — куда указывает его балансер ([Exits.effective]), в том числе общий.
     * [hints] — подсказки белых списков из паспорта подписки (по `Server.key`): порядок перебора при белых списках.
     * [onBoard] — сводный экран после каждой буквы любого трубопровода; последний — итог.
     */
    suspend fun press(
        warm: Warm,
        services: Set<Service>,
        os: OsNetwork,
        prior: Prior = Prior.None,
        hints: Map<String, WhiteHint> = emptyMap(),
        onBoard: (Board) -> Unit = {},
    ): Board {
        val servers = warm.servers
        val memory = store.loadMemory()
        // Снимок выходов всех сервисов — до старта любого трубопровода; доска с первого экрана — «ищу» у всех.
        val letters = services.associate { s ->
            val exit = exits.effective(s.id)?.takeIf { id -> servers.any { it.id == id } }
            s.id to Letter.Pressed(clock.nowMs(), os, servers, exit, memory, prior, s.id, exits.pinned(s.id), hints)
        }
        val boards = Boards(services, onBoard, services.associate { it.id to render(listOf(letters.getValue(it.id)), it) })
        val screens = pipes(services, letters::getValue) { service, pressed, hub, inbox, lead ->
            val run = act(pressed, PressMachine(service.id), pressTimeoutMs, executor(service, servers, hub, lead), tape, { _, letters ->
                boards.put(service.id, render(letters, service))
            }, inbox)
            render(run.letters, service, closedAtMs = clock.nowMs().takeIf { run.closed })
        }
        return board(services, screens).also(onBoard)
    }

    /**
     * Экран открыт: разовый замер сети напрямую и проба выхода каждого сервиса (openStep), без перебора; открытие —
     * тоже трубопровод на сервис. Выход, которого нет в подписке, — Vpn.Unlisted: проба ему не запускается.
     * [prior] — раунды линии, снятые до Xray ([detectLine]): свежие (≤ LINE_FRESH_MS) входят в замер сразу, линия
     * заново не меряется — плашка готова с первого экрана; подтверждающий раунд при подозрении машина досылает сама.
     * [onBoard] — сводный экран после каждой буквы, последний — экран открытия (с учётом срока).
     */
    suspend fun open(
        warm: Warm,
        services: Set<Service>,
        os: OsNetwork,
        prior: Prior = Prior.None,
        onBoard: (Board) -> Unit = {},
    ): Opening {
        val servers = warm.servers
        val known = freshRounds(prior, clock.nowMs())
        val boards = Boards(services, onBoard, emptyMap())
        val runs = pipes(services, { exits.effective(it) }) { service, exit, hub, inbox, lead ->
            val vpn = when (exit) {
                null -> Vpn.Off
                else -> servers.find { it.id == exit }?.let(Vpn::On) ?: Vpn.Unlisted(exit)
            }
            val opened = Letter.Opened(clock.nowMs(), os, vpn)
            // Раунды до Xray машина получает на старте — они в шапке прогона (Head.Open), а не нотой.
            act(opened, OpenMachine(opened, known, service.id), OPEN_TIMEOUT_MS, executor(service, servers, hub, lead), tape, { s, _ ->
                boards.put(service.id, renderOpened(s, closed = false, service = service))
            }, inbox)
        }
        val screens = services.associate { s -> s.id to renderOpened(runs.getValue(s.id).state, runs.getValue(s.id).closed, s) }
        return Opening(board(services, screens).also(onBoard), priorOf(known, runs.values))
    }

    /**
     * Анализ подписки на прогретой сессии: свой прогон актора (ScanMachine) и своя шапка Ленты (Head.Scan) — по всем
     * серверам [warm] и всему набору [services] сразу, не трубопровод на сервис. Балансеры не трогает: пробы и эталон
     * идут через SOCKS-inbound кандидата. Память — в общее хранилище под тем же замком, что у нажатия; паспорт —
     * слиянием с прошлым. Прервали (отмена, срок) — сохраняется частичный паспорт: только уже разобранные серверы.
     * [sniWhite] — ключи серверов, чей SNI в белом списке SNI: отдельный факт карточки и паспорта.
     * [onBoard] — экран анализа после каждой буквы (доска по шагам — [ScanFold], без повторной свёртки всех писем);
     * возвращается последний.
     */
    suspend fun analyze(
        warm: Warm,
        services: Set<Service>,
        os: OsNetwork,
        prior: Prior,
        hints: Map<String, WhiteHint>,
        sniWhite: Set<String> = emptySet(),
        onBoard: (ScanBoard) -> Unit = {},
    ): ScanBoard = coroutineScope {
        val ordered = services.sortedWith(SERVICE_ORDER)
        val scan = Letter.Scan(clock.nowMs(), os, warm.servers, ordered.map { it.id }, hints, prior, store.loadMemory(), sniWhite, confirmFits = true)
        val lines = Job(coroutineContext[Job])
        val hub = LineHub(line, clock, CoroutineScope(coroutineContext + lines))
        val inbox = Channel<Letter>(Channel.UNLIMITED).also(hub::subscribe)
        // Последнее состояние машины и сохранён ли паспорт делом — для частичного паспорта при прерывании.
        var last: ScanState = ScanState.Idle
        var saved = false
        // Доска по шагам: observe актора зовётся по одному, на каждую букву (последняя в letters) — свёртка на шаг.
        var fold = ScanFold(services)
        try {
            act(scan, ScanMachine, scanTimeoutMs, scanExecutor(ordered, warm.servers, hub) { saved = true }, tape, { s, letters ->
                last = s
                fold = fold.next(letters.last(), s)
                onBoard(fold.board())
            }, inbox)
            fold.board().also(onBoard)
        } finally {
            lines.cancel()
            if (!saved) withContext(NonCancellable) { savePartial(last) }
        }
    }

    /** Анализ прерван раньше паспорта: разобранные серверы — в хранилище слиянием; разобранных нет — паспорт прежний. */
    private fun savePartial(state: ScanState) {
        val partial = state.partialPassport(clock.nowMs())?.takeIf { it.servers.isNotEmpty() } ?: return
        savePassport(partial)
        tape.write(noteLine("scan: прерван, частичный паспорт — серверов: ${partial.servers.size}"))
    }

    private fun savePassport(p: Passport) = passportStore.save(passportStore.load()?.merged(p) ?: p)

    /**
     * Дела анализа портами. Проба — сервиса из дела (его ProbeSpec), ответ несёт тот же сервис: по нему машина находит
     * задачу в полёте. Выход — эталоном через SOCKS кандидата, страна — локальным geoip. Remember несёт успехи всех
     * сервисов прогона — слияние по каждому. [onSaved] — паспорт сохранён делом, частичный не нужен.
     */
    private fun scanExecutor(
        services: List<Service>,
        servers: List<Server>,
        hub: LineHub,
        onSaved: () -> Unit,
    ): suspend CoroutineScope.(Deed, SendChannel<Letter>) -> Unit {
        val byId = servers.associateBy { it.id }
        val byService = services.associateBy { it.id }
        return { deed, inbox ->
            when (deed) {
                Deed.ProbeLine -> hub.request()
                is Deed.Probe -> launch {
                    val server = byId.getValue(deed.server)
                    val spec = byService.getValue(deed.service).probe
                    val verdict = gate.withEndpoint(server.endpoint) { prober.probe(spec, server, socksPortOf(deed.server, servers, opts)) }
                    inbox.send(Letter.ProbeAnswered(clock.nowMs(), deed.server, verdict, deed.service))
                }
                is Deed.MeasureExit -> launch {
                    val server = byId.getValue(deed.server)
                    // Эталон ходит тем же TCP до того же IP:порт — под тем же потолком, что пробы.
                    val ip = gate.withEndpoint(server.endpoint) { exitProbe.exitIp(socksPortOf(deed.server, servers, opts)) }
                    val exit = ip?.let { Exit.At(it, countryOf(it) ?: "?") } ?: Exit.Unknown
                    inbox.send(Letter.ExitSeen(clock.nowMs(), deed.server, exit))
                }
                is Deed.Remember -> memoryLock.withLock {
                    store.saveMemory(services.fold(store.loadMemory()) { m: Memory, s -> m.merged(deed.memory, s.id) })
                }
                is Deed.SavePassport -> { savePassport(deed.passport); onSaved() }
                // Анализ балансеры не трогает и скорость не меряет: машина этих дел не выдаёт.
                is Deed.MakeExit, is Deed.MeasureSpeed -> Unit
            }
        }
    }

    /**
     * Последние рабочие выходы этой сети — в Xray, по сервису (Exits.make, как после подбора). Сеть — по раундам линии
     * [prior] и ключу памяти ядра (memoryKey) каждого сервиса; выход — первый успех ключа, который есть в подписке и
     * не под блоком IP:порт. Не перебор: один выход из памяти на сервис, проверит его открытие. Сервиса нет в итоге —
     * восстанавливать нечего (линии нет, памяти нет, сервера нет в подписке) или Xray не принял выход.
     */
    suspend fun recall(warm: Warm, services: Set<Service>, os: OsNetwork, prior: Prior): Map<ServiceId, Server> {
        val rounds = freshRounds(prior, clock.nowMs()).ifEmpty { return emptyMap() }
        val scenario = rounds.fold<Letter.LineObserved, Diagnosis>(Diagnosis.Pending) { d, l -> d.observe(os, l.line) }.scenario
            ?: return emptyMap()
        val memory = store.loadMemory()
        val now = clock.nowMs()
        return services.sortedBy { it.id.id }.mapNotNull { service ->
            val server = memory.successes[memoryKey(os, scenario, service.id)]?.firstNotNullOfOrNull { success ->
                warm.servers.find { it.key == success.serverKey }?.takeUnless { memory.isBlocked(it.endpoint, now) }
            } ?: return@mapNotNull null
            exits.make(service.id, server.id).fold(
                { e -> tape.write(noteLine("recall ${service.id.id} ${server.id.tag}: setExit $e")); null },
                { tape.write(noteLine("recall ${service.id.id} ${server.id.tag}: из памяти сети")); service.id to server },
            )
        }.toMap()
    }

    /** Раунды линии, ещё годные сейчас (тот же срок LINE_FRESH_MS, что у открытия и восстановления); иначе None. */
    fun fresh(prior: Prior): Prior =
        freshRounds(prior, clock.nowMs()).toNonEmptyListOrNull()?.let(Prior::Detected) ?: Prior.None

    /**
     * Снять выход сервиса [service] (Exits.clear): его балансер снова повторяет общий; у GENERAL_FOLLOWS — и общий
     * балансировщик Xray выбирает сам. Память не трогается — её меняет только нажатие; на Ленте — только тег.
     */
    suspend fun dropExit(service: ServiceId): Either<EngineError, Unit> {
        val tag = exits.effective(service)?.tag ?: "—"
        return exits.clear(service)
            .onRight { tape.write(noteLine("dropExit ${service.id} $tag: выход снят, Xray выбирает сам")) }
            .onLeft { tape.write(noteLine("dropExit ${service.id} $tag: $it")) }
    }

    /**
     * Закрепить за [service] выход [server], прошедший пробу сервиса (Exits.make): так он переживает снятие общего,
     * за которым сервис шёл. Память не трогается; на Ленте — только тег.
     */
    suspend fun pinExit(service: ServiceId, server: ServerId): Either<EngineError, Unit> =
        exits.make(service, server)
            .onRight { tape.write(noteLine("pinExit ${service.id} ${server.tag}: прошёл пробу сервиса, закреплён")) }
            .onLeft { tape.write(noteLine("pinExit ${service.id} ${server.tag}: $it")) }

    /** Адрес — разрешённый IP; вход — по правилу geoip:ru самого Xray (TestRoute → direct). */
    private suspend fun locate(s: Server): Server {
        val ip = resolver.resolve(s.endpoint.host) ?: return s
        val entry = api.routeOf(ip).fold({ Entry.Unknown }, { if (it == DIRECT) Entry.Ru else Entry.Foreign(countryOf(ip) ?: "?") })
        return s.copy(endpoint = Endpoint(ip, s.endpoint.port), entry = entry)
    }

    /**
     * Трубопроводы [services] параллельно на одной линии. Старт детерминирован: сначала все ящики подписаны на хаб и
     * снят снимок выходов [snapshot] для всех сервисов, потом — акторы; трубопровод общего выхода (GENERAL_FOLLOWS)
     * стартует первым, а его первая проба первой встаёт к потолку на адрес: [lead] — прочие трубопроводы ждут его,
     * прежде чем пробовать (иначе на настоящих потоках Инста занимала общий адрес первой). Он завершён, когда
     * общий выдал первую пробу или кончился; общего в наборе нет — сразу. Замер линии, переживший трубопроводы,
     * отменяется: его букву уже некому читать.
     */
    private suspend fun <S, T> pipes(
        services: Set<Service>,
        snapshot: (ServiceId) -> S,
        pipe: suspend (Service, S, LineHub, Channel<Letter>, CompletableDeferred<Unit>) -> T,
    ): Map<ServiceId, T> = coroutineScope {
        val lines = Job(coroutineContext[Job])
        val hub = LineHub(line, clock, CoroutineScope(coroutineContext + lines))
        val lead = CompletableDeferred<Unit>()
        if (services.none { it.id == GENERAL_FOLLOWS }) lead.complete(Unit)
        services.sortedWith(SERVICE_ORDER)
            .map { s -> Triple(s, snapshot(s.id), Channel<Letter>(Channel.UNLIMITED).also(hub::subscribe)) }
            .map { (s, snap, inbox) ->
                async {
                    try {
                        s.id to pipe(s, snap, hub, inbox, lead)
                    } finally {
                        if (s.id == GENERAL_FOLLOWS) lead.complete(Unit)
                    }
                }
            }
            .awaitAll().toMap()
            .also { lines.cancel() }
    }

    /**
     * Дела трубопровода [service] портами: пробы — дети области проб актора, ответ — буквой в его ящик. Проба стартует
     * без диспетчеризации: место на адресе берётся сразу, в порядке выдачи дел. Не общий трубопровод пробует после
     * [lead] (см. [pipes]); общий завершает его своей первой пробой — уже вставшей к потолку.
     */
    private fun executor(
        service: Service,
        servers: List<Server>,
        hub: LineHub,
        lead: CompletableDeferred<Unit>,
    ): suspend CoroutineScope.(Deed, SendChannel<Letter>) -> Unit {
        val byId = servers.associateBy { it.id }
        val general = service.id == GENERAL_FOLLOWS
        return { deed, inbox ->
            when (deed) {
                Deed.ProbeLine -> hub.request()
                is Deed.Probe -> {
                    if (!general) lead.await()
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        val server = byId.getValue(deed.server)
                        val verdict = gate.withEndpoint(server.endpoint, priority = general) {
                            prober.probe(service.probe, server, socksPortOf(deed.server, servers, opts))
                        }
                        inbox.send(Letter.ProbeAnswered(clock.nowMs(), deed.server, verdict))
                    }
                    if (general) lead.complete(Unit)
                }
                is Deed.MakeExit -> exits.make(service.id, deed.server).onLeft { tape.write(noteLine("setExit ${service.id.id}: $it")) }
                is Deed.MeasureSpeed -> tape.write(noteLine("MeasureSpeed ${deed.server.tag}: скорость пока не меряется"))
                is Deed.Remember -> memoryLock.withLock { store.saveMemory(store.loadMemory().merged(deed.memory, service.id)) }
                // Дела анализа подписки: нажатие их не выдаёт, у анализа свой scanExecutor.
                is Deed.MeasureExit, is Deed.SavePassport -> Unit
            }
        }
    }
}

/**
 * Последние экраны трубопроводов: после каждой буквы любого — сводный экран, по одному (буквы идут из разных корутин).
 * [initial] — экраны до первой буквы трубопровода (у нажатия — «ищу»), чтобы сосед не мигал «—».
 */
private class Boards(private val services: Set<Service>, private val onBoard: (Board) -> Unit, initial: Map<ServiceId, Screen>) {
    private val lock = SynchronizedObject()
    private var screens = initial

    fun put(service: ServiceId, screen: Screen) = synchronized(lock) {
        screens = screens + (service to screen)
        onBoard(board(services, screens))
    }
}

/**
 * Раунды открытия по порядку: [known] и снятые за открытие. Хаб шлёт каждый раунд во все ящики в одном порядке,
 * поэтому раунды любого прогона — начало общего списка; самый длинный — весь.
 */
private fun priorOf(known: List<Letter.LineObserved>, runs: Collection<Run<*>>): Prior {
    val observed = runs.map { run -> run.letters.filterIsInstance<Letter.LineObserved>() }.maxByOrNull { it.size }.orEmpty()
    return (known + observed).toNonEmptyListOrNull()?.let(Prior::Detected) ?: Prior.None
}

/** Раунды, ещё годные для замера при открытии [atMs] (по последнему, как freshPrior нажатия); иначе пусто. */
private fun freshRounds(prior: Prior, atMs: Long): List<Letter.LineObserved> = when (prior) {
    Prior.None -> emptyList()
    is Prior.Detected -> if (atMs - prior.rounds.last().atMs in 0..LINE_FRESH_MS) prior.rounds else emptyList()
}

/**
 * Линия при открытии экрана — до Xray и без него: рукопожатия к эталонам идут мимо туннеля (приложение исключено из
 * своего VPN), поэтому плашке сети не нужно ждать подъёма сессии. Машина открытия с Vpn.Off — VPN в этот момент
 * ещё не поднят; о сервисах открытие до Xray не знает ничего, кроме сети: прогон — GENERAL_FOLLOWS, карточки
 * сервисов [services] — «—». Раунды ([Opening.prior]) — в [Driver.open].
 */
suspend fun detectLine(
    line: LineProbe,
    clock: Clock,
    tape: TapeSink,
    os: OsNetwork,
    services: Set<Service>,
    onBoard: (Board) -> Unit = {},
): Opening {
    val opened = Letter.Opened(clock.nowMs(), os, Vpn.Off)
    fun boardOf(screen: Screen) = board(services, mapOf(GENERAL_FOLLOWS to screen))
    val run = act(opened, OpenMachine(opened, emptyList(), GENERAL_FOLLOWS), OPEN_TIMEOUT_MS, { deed, inbox ->
        when (deed) {
            Deed.ProbeLine -> launch { inbox.send(Letter.LineObserved(clock.nowMs(), line.observe())) }
            // Машина с Vpn.Off других дел не даёт: пробовать и выставлять нечего.
            is Deed.Probe, is Deed.MakeExit, is Deed.MeasureSpeed, is Deed.Remember, is Deed.MeasureExit, is Deed.SavePassport -> Unit
        }
    }, tape, { s, _ -> onBoard(boardOf(renderOpened(s, closed = false))) })
    val board = boardOf(renderOpened(run.state, closed = run.closed)).also(onBoard)
    return Opening(board, priorOf(emptyList(), listOf(run)))
}
