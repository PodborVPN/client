package podbor.core

/**
 * Свой бюджет анализа: задач всего разом — больше, чем у нажатия (88 серверов шли ~3 мин при цели ~2);
 * на один IP:порт — по-прежнему одна (Budget.perEndpoint).
 */
const val SCAN_BUDGET_TOTAL = 12

/** Задача анализа на сервере — где выход или проба сервиса набора. Бюджет — по адресу сервера. */
sealed interface ScanTask {
    val server: Server

    data class MeasureExit(override val server: Server) : ScanTask
    data class Probe(override val server: Server, val service: ServiceId) : ScanTask
}

sealed interface ScanState {
    data object Idle : ScanState

    data class Running(
        val scan: Letter.Scan,
        val diagnosis: Diagnosis,
        val memory: Memory,
        /** Допустимые сценарием серверы — они и лягут в паспорт; пусто, пока сценария нет. */
        val targets: List<Server>,
        val queue: List<ScanTask>,
        val inFlight: List<ScanTask>,
        /** Задачи, которым неоднозначный провал уже дали повторить. */
        val retried: Set<ScanTask>,
        /** Пробы с первым успехом, ждущие подтверждения вторым ([Letter.Scan.confirmFits]). */
        val confirming: Set<ScanTask> = emptySet(),
        /** Адреса, уже раз ответившие тишиной: вторая — блок (как в нажатии, но тишина — подпись адреса, не сервиса). */
        val silent: Set<Endpoint>,
        val exits: Map<ServerId, Exit>,
        /** Первый ответ через туннель по серверу — Seen.Answered. */
        val answered: Map<ServerId, Long>,
    ) : ScanState

    data class Done(val passport: Passport, val diagnosis: Diagnosis) : ScanState
}

/**
 * Машина анализа подписки: линия → допустимые сценарием серверы → по каждому выход и пробы набора сервисов → паспорт.
 * Сервисы — только из письма Scan: ни одного имени здесь нет.
 */
fun scanStep(s: ScanState, l: Letter): Pair<ScanState, List<Deed>> = when (l) {
    is Letter.Scan -> scanStart(l)
    is Letter.LineObserved -> when (s) {
        is ScanState.Running -> onLine(s, l)
        ScanState.Idle, is ScanState.Done -> s to emptyList()
    }
    is Letter.ExitSeen -> when (s) {
        is ScanState.Running -> onExit(s, l)
        ScanState.Idle, is ScanState.Done -> s to emptyList()
    }
    is Letter.ProbeAnswered -> when (s) {
        is ScanState.Running -> onProbe(s, l)
        ScanState.Idle, is ScanState.Done -> s to emptyList()
    }
    // Экран и нажатие — свои машины.
    is Letter.Opened, is Letter.Pressed -> s to emptyList()
}

private fun scanStart(l: Letter.Scan): Pair<ScanState, List<Deed>> {
    val running = ScanState.Running(
        scan = l, diagnosis = Diagnosis.Pending, memory = l.memory, targets = emptyList(), queue = emptyList(),
        inFlight = emptyList(), retried = emptySet(), silent = emptySet(), exits = emptyMap(), answered = emptyMap(),
    )
    return when (val prior = l.prior.freshAt(l.atMs)) {
        Prior.None -> running to listOf(Deed.ProbeLine)
        // Линию уже снял детектор — как в нажатии, без второго замера.
        is Prior.Detected -> {
            val diagnosis = prior.rounds.fold<Letter.LineObserved, Diagnosis>(Diagnosis.Pending) { d, r -> d.observe(l.os, r.line) }
            decide(running.copy(diagnosis = diagnosis), prior.rounds.last().atMs)
        }
    }
}

private fun onLine(s: ScanState.Running, l: Letter.LineObserved): Pair<ScanState, List<Deed>> = when (s.diagnosis) {
    Diagnosis.Pending, is Diagnosis.Suspected, is Diagnosis.Rechecking -> decide(s.copy(diagnosis = s.diagnosis.observe(s.scan.os, l.line)), l.atMs)
    is Diagnosis.Settled -> s to emptyList()
}

/**
 * Сценарий → задачи. Подозрение ждёт подтверждения: анализу важен итог по всей подписке, а не первый выход,
 * так что зарубежные не откладываем на потом, а просто ждём один раунд. Допустимость и порядок — [plan], как в нажатии.
 */
private fun decide(s: ScanState.Running, atMs: Long): Pair<ScanState, List<Deed>> = when (val d = s.diagnosis) {
    Diagnosis.Pending, is Diagnosis.Rechecking -> s to emptyList()
    is Diagnosis.Suspected -> s to listOf(Deed.ProbeLine)
    is Diagnosis.Settled -> {
        // Успехи убраны: порядок анализа — порядок политики, общий для всех сервисов. Ключ plan читает только для
        // успехов (память первой), а их нет — поэтому заглушка ServiceId("") безвредна; блоки ключа не требуют.
        val order = s.memory.copy(successes = emptyMap())
        val targets = plan(d.scenario, s.scan.servers, order, memoryKey(s.scan.os, d.scenario, ServiceId("")), atMs, s.scan.hints).queue
        // Выход — в первой очереди: сначала по всем серверам, потом сервисы.
        val queue = targets.map(ScanTask::MeasureExit) + targets.flatMap { srv -> s.scan.services.map { ScanTask.Probe(srv, it) } }
        launch(s.copy(targets = targets, queue = queue), atMs)
    }
}

private fun onExit(s: ScanState.Running, l: Letter.ExitSeen): Pair<ScanState, List<Deed>> {
    val task = s.inFlight.find { it is ScanTask.MeasureExit && it.server.id == l.server } ?: return s to emptyList()
    val after = s.copy(inFlight = s.inFlight - task, exits = s.exits + (l.server to l.exit))
    return launch(
        when (l.exit) {
            is Exit.At -> after.answeredBy(task.server, l.atMs)
            Exit.Unknown -> after
        },
        l.atMs,
    )
}

private fun onProbe(s: ScanState.Running, a: Letter.ProbeAnswered): Pair<ScanState, List<Deed>> {
    val task = s.inFlight.filterIsInstance<ScanTask.Probe>().find { it.server.id == a.server && it.service == a.service }
        ?: return s to emptyList()
    val after = s.copy(inFlight = s.inFlight - task)
    val endpoint = task.server.endpoint
    val kind = a.verdict.kind()
    // Подтверждение: второй ответ не успех — шум момента; сервер ответил, но ни памяти, ни повтора.
    if (task in s.confirming && kind != VerdictKind.Fits) {
        return launch(after.copy(confirming = after.confirming - task).answeredBy(task.server, a.atMs), a.atMs)
    }
    return when (kind) {
        // Первый успех при подтверждении — в конец очереди ещё раз: засчитает второй.
        VerdictKind.Fits if s.scan.confirmFits && task !in s.confirming ->
            launch(after.answeredBy(task.server, a.atMs).copy(confirming = after.confirming + task, queue = after.queue + task), a.atMs)
        VerdictKind.Fits -> {
            val memory = s.diagnosis.scenario?.let { after.memory.rememberSuccess(memoryKey(s.scan.os, it, task.service), task.server, a.atMs) }
                ?: after.memory
            remember(after.answeredBy(task.server, a.atMs).copy(memory = memory, confirming = after.confirming - task), a.atMs)
        }
        // Рукопожатие прошло — сервер жив, хоть сервис и молчит; повтор один раз.
        VerdictKind.ServiceDead -> launch(after.answeredBy(task.server, a.atMs).retry(task), a.atMs)
        // Холодный старт тоже даёт тишину — блок только по второй на этом IP:порт; остальное по адресу снимается.
        VerdictKind.TlsSilence ->
            if (endpoint in s.silent) {
                remember(after.copy(memory = after.memory.block(endpoint, a.atMs), queue = after.queue.filter { it.server.endpoint != endpoint }), a.atMs)
            } else {
                launch(after.copy(silent = after.silent + endpoint).retry(task), a.atMs)
            }
        // TCP до сервера нет: выход и пробы всех сервисов идут тем же TCP до того же IP:порт — упрутся туда же,
        // а время анализа уйдёт на таймауты. Не приговор: в паспорте Silent, следующий анализ спросит снова.
        VerdictKind.Unreachable -> launch(after.copy(queue = after.queue.filter { it.server.id != a.server }), a.atMs)
    }
}

/** Ответ через адрес: сервер жив; тишина адреса сбрасывается — блок только по двум тишинам подряд. */
private fun ScanState.Running.answeredBy(server: Server, atMs: Long): ScanState.Running =
    copy(
        answered = if (server.id in answered) answered else answered + (server.id to atMs),
        silent = silent - server.endpoint,
    )

/** Неоднозначный провал — в конец очереди, один раз. */
private fun ScanState.Running.retry(task: ScanTask): ScanState.Running =
    if (task in retried) this else copy(queue = queue + task, retried = retried + task)

/** Память сменилась (успех, блок) — сразу краю: подбор на этой сети знает о ней, даже если анализ прервут. */
private fun remember(s: ScanState.Running, atMs: Long): Pair<ScanState, List<Deed>> {
    val (next, deeds) = launch(s, atMs)
    return next to listOf(Deed.Remember(s.memory)) + deeds
}

/** Добрать задачи по бюджету; задач нет и в полёте пусто — паспорт готов. */
private fun launch(s: ScanState.Running, atMs: Long): Pair<ScanState, List<Deed>> {
    val start = pickStartable(s.queue, s.inFlight, Budget(total = SCAN_BUDGET_TOTAL)) { it.server.endpoint }
    val next = s.copy(queue = s.queue - start.toSet(), inFlight = s.inFlight + start)
    if (next.queue.isNotEmpty() || next.inFlight.isNotEmpty()) return next to start.map(::deedOf)
    val passport = next.passport(atMs, next.targets)
    return ScanState.Done(passport, next.diagnosis) to listOf(Deed.SavePassport(passport))
}

private fun deedOf(task: ScanTask): Deed = when (task) {
    is ScanTask.MeasureExit -> Deed.MeasureExit(task.server.id)
    is ScanTask.Probe -> Deed.Probe(task.server.id, task.service)
}

/**
 * Паспорт при прерывании анализа («ОК» раньше конца): только серверы, которые ответили или прошли все свои задачи.
 * Ждущие в очереди или в полёте не попадают (не Silent): следующий анализ проверит их как новые. Idle — паспорта нет.
 */
fun ScanState.partialPassport(atMs: Long): Passport? = when (this) {
    ScanState.Idle -> null
    is ScanState.Done -> passport
    is ScanState.Running -> {
        val pending = (queue + inFlight).map { it.server.id }.toSet()
        passport(atMs, targets.filter { it.id in answered || it.id !in pending })
    }
}

/** Факты по [servers]; молчание — Silent «сейчас», не приговор: следующий анализ спросит снова. */
private fun ScanState.Running.passport(atMs: Long, servers: List<Server>): Passport = Passport(
    servers = servers.associate { srv ->
        srv.key to ServerFacts(
            transport = srv.transport,
            entry = srv.entry,
            whiteHint = scan.hints[srv.key] ?: WhiteHint.Unlikely,
            exit = exits[srv.id] ?: Exit.Unknown,
            seen = answered[srv.id]?.let(Seen::Answered) ?: Seen.Silent,
            sniWhite = srv.key in scan.sniWhite,
        )
    },
    analyzedAtMs = atMs,
)
