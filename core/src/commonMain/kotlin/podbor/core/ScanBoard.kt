package podbor.core

/** Сколько серверов подписки ответило сервису через туннель. */
data class ServiceTally(val service: ServiceId, val label: String, val works: Int)

/** Метка сервиса на карточке: ещё меряем, работает, не работает. */
enum class Mark3 { Pending, Works, Dead }

/** Карточка сервера на экране анализа; [services] — метка сервиса → отметка, в порядке набора. */
data class ServerCard(
    val key: String,
    val entry: Entry,
    val exit: Exit,
    val whiteHint: WhiteHint,
    /** SNI в белом списке SNI — пометка карточки, на [whiteHint] не влияет. */
    val sniWhite: Boolean,
    val transport: Transport,
    val services: List<Pair<String, Mark3>>,
    /** Хоть одна задача по серверу в полёте. */
    val live: Boolean,
)

/** Экран анализа как значение: сводка, лента карточек (новейшая первой), итог. */
data class ScanBoard(
    val total: Int,
    val done: Int,
    val ruEntries: Int,
    val likelyWhite: Int,
    val tallies: List<ServiceTally>,
    val cards: List<ServerCard>,
    val verdict: List<String>,
    val finished: Boolean,
)

private val EMPTY_BOARD = ScanBoard(0, 0, 0, 0, emptyList(), emptyList(), emptyList(), false)

/**
 * Производная Ленты, как [render]: свёртка [scanStep] по письмам, так что экран не расходится с машиной.
 * Карточка появляется, когда машина впервые выдала дело по серверу. Сервисы — только из [services] (метки из описаний).
 */
fun scanBoard(services: Set<Service>, letters: List<Letter>): ScanBoard {
    var state: ScanState = ScanState.Idle
    return letters.fold(ScanFold(services)) { fold, letter ->
        state = scanStep(state, letter).first
        fold.next(letter, state)
    }.board()
}

/**
 * Доска анализа по шагам: что нужно экрану сверх состояния машины (порядок старта серверов, успехи), — накоплено,
 * поэтому край обновляет доску на каждую букву без повторной свёртки всех писем (было O(n²) на прогон).
 * [next] — буква и состояние машины после шага по ней; [board] — доска сейчас, за O(задачи + серверы × сервисы).
 */
class ScanFold private constructor(
    private val services: Set<Service>,
    private val state: ScanState,
    private val scan: Begun,
    /** Сервисы письма Scan, известные экрану, в порядке набора. */
    private val shown: List<Service>,
    private val byId: Map<ServerId, Server>,
    /** Серверы в порядке первой выданной задачи. */
    private val started: List<ServerId>,
    private val startedSet: Set<ServerId>,
    private val works: Set<Pair<ServerId, ServiceId>>,
) {
    constructor(services: Set<Service>) : this(services, ScanState.Idle, Begun.No, emptyList(), emptyMap(), emptyList(), emptySet(), emptySet())

    /** Письмо Scan прогона: ещё не пришло — доска пуста. */
    private sealed interface Begun {
        data object No : Begun
        data class Yes(val scan: Letter.Scan) : Begun
    }

    /** Письмо Scan: прогон, его сервисы и серверы — с него; накопленное по шагам остаётся. */
    private fun began(l: Letter.Scan): ScanFold = ScanFold(
        services, state, Begun.Yes(l), l.services.mapNotNull { id -> services.find { it.id == id } }, l.servers.associateBy { it.id },
        started, startedSet, works,
    )

    /**
     * Буква [letter] и состояние машины [after] после шага по ней. Новые задачи шага машина дописывает в конец
     * inFlight в порядке выдачи дел — серверы, которых там раньше не было, и есть стартовавшие на этом шаге.
     */
    fun next(letter: Letter, after: ScanState): ScanFold {
        val base = if (letter is Letter.Scan) began(letter) else this
        // При подтверждении ✓ — только второй успех: первый держит «…» (проба снова в очереди).
        val fits = letter is Letter.ProbeAnswered && state is ScanState.Running && letter.verdict.kind() == VerdictKind.Fits &&
            (!state.scan.confirmFits || state.confirming.any { it is ScanTask.Probe && it.server.id == letter.server && it.service == letter.service })
        val fresh = when (after) {
            is ScanState.Running -> after.inFlight.map { it.server.id }.filter { it !in startedSet }.distinct()
            ScanState.Idle, is ScanState.Done -> emptyList()
        }
        return ScanFold(
            services = services,
            state = after,
            scan = base.scan,
            shown = base.shown,
            byId = base.byId,
            started = if (fresh.isEmpty()) started else started + fresh,
            startedSet = if (fresh.isEmpty()) startedSet else startedSet + fresh,
            works = if (fits) works + (letter.server to letter.service) else works,
        )
    }

    fun board(): ScanBoard = when (scan) {
        Begun.No -> EMPTY_BOARD
        is Begun.Yes -> boardOf(scan.scan)
    }

    private fun boardOf(begun: Letter.Scan): ScanBoard {
        val inFlight = when (val s = state) {
            is ScanState.Running -> s.inFlight
            ScanState.Idle, is ScanState.Done -> emptyList()
        }
        val tasks = when (val s = state) {
            is ScanState.Running -> s.queue + s.inFlight
            ScanState.Idle, is ScanState.Done -> emptyList()
        }
        // Выход: замер этого прогона, иначе — из паспорта законченного анализа.
        val exits = when (val s = state) {
            is ScanState.Running -> s.exits
            ScanState.Idle, is ScanState.Done -> emptyMap()
        }
        val passportExits = when (val s = state) {
            is ScanState.Done -> s.passport.servers.mapValues { it.value.exit }
            ScanState.Idle, is ScanState.Running -> emptyMap()
        }
        val pendingIds = tasks.mapTo(HashSet()) { it.server.id }
        val probing = tasks.filterIsInstance<ScanTask.Probe>().mapTo(HashSet()) { it.server.id to it.service }
        val live = inFlight.mapTo(HashSet()) { it.server.id }
        val cards = started.asReversed().mapNotNull(byId::get).map { srv ->
            ServerCard(
                key = srv.key,
                entry = srv.entry,
                exit = exits.getOrElse(srv.id) { passportExits.getOrElse(srv.key) { Exit.Unknown } },
                whiteHint = begun.hints.getOrElse(srv.key) { WhiteHint.Unlikely },
                sniWhite = srv.key in begun.sniWhite,
                transport = srv.transport,
                services = shown.map { svc ->
                    svc.label to when (srv.id to svc.id) {
                        in works -> Mark3.Works
                        in probing -> Mark3.Pending
                        else -> Mark3.Dead
                    }
                },
                live = srv.id in live,
            )
        }
        val tallies = shown.map { svc -> ServiceTally(svc.id, svc.label, works.count { it.second == svc.id }) }
        val likelyWhite = begun.servers.count { begun.hints[it.key] == WhiteHint.Likely }
        val diagnosis = when (val s = state) {
            is ScanState.Running -> s.diagnosis
            is ScanState.Done -> s.diagnosis
            ScanState.Idle -> Diagnosis.Pending
        }
        val finished = state is ScanState.Done
        val whiteLine = if (likelyWhite > 0) "Белые списки: вероятно ${serversCount(likelyWhite)}" else "Белые списки: в этой подписке нет подходящих серверов"
        val open = if (finished) listOf("Обычная сеть: " + tallies.joinToString(" · ") { "${it.label} ✓ ${it.works}" }, whiteLine) else emptyList()
        val verdict = diagnosis.scene.fold({ open }) { verdictOf(it, finished, whiteLine, open) }
        return ScanBoard(
            total = begun.servers.size,
            // Закончили — все разобраны: отсеянные сценарием и блоком тоже «сделаны», табло сходится total/total.
            done = if (finished) begun.servers.size else started.count { it !in pendingIds },
            ruEntries = begun.servers.count { it.entry == Entry.Ru },
            likelyWhite = likelyWhite,
            tallies = tallies,
            cards = cards,
            verdict = verdict,
            finished = finished,
        )
    }
}

/** Итог анализа по сценарию; [open] — итог обычной сети (он же — пока сценария нет). */
private fun verdictOf(scenario: Scenario, finished: Boolean, whiteLine: String, open: List<String>): List<String> = when (scenario) {
    // Подозрение анализ проверяет как белые списки (plan) — и говорит о нём так же.
    Scenario.MobileWhitelist, Scenario.SuspectWhitelist ->
        listOf("сейчас белые списки — проверил только вероятно-белые; обычную сеть проверю, когда она вернётся") +
            if (finished) listOf(whiteLine) else emptyList()
    // Сети нет — серверы не проверялись: «✓ 0» было бы неправдой о них.
    Scenario.Offline -> listOf("нет сети — проверю серверы, когда она появится")
    Scenario.MobileOpen, Scenario.Wifi, Scenario.Unclear -> open
}
