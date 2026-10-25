package podbor.core

/** Сколько провалов на разных IP без успеха — подпись «все умерли разом», повод снять линию заново. */
const val REDIAGNOSE_AFTER_FAILURES = 4

/** Перепроверок линии за нажатие — не больше. */
const val MAX_REDIAGNOSES = 2

sealed interface PressState {
    data object Idle : PressState

    data class Running(
        val pressed: Letter.Pressed,
        val letters: List<Letter>,
        val memory: Memory,
        val queue: List<Server>,
        val inFlight: List<Server>,
        /** Серверы, которым неоднозначный провал уже дали повторить (одиночный провал повторяется один раз). */
        val retried: Set<ServerId>,
        val rediagnoses: Int,
        /** Диагноз линии; перепроверка начинает новый (Rechecking), прежний сценарий в силе до нового раунда. */
        val diagnosis: Diagnosis,
    ) : PressState

    /** diagnosis — с каким диагнозом нажатие закончилось (Pending — выход подтвердился до линии; Rechecking — во время перепроверки). */
    data class Done(val letters: List<Letter>, val diagnosis: Diagnosis) : PressState
}

/** Машина нажатия: внешняя держит сценарий, внутренняя — перебор в нём. */
fun step(state: PressState, letter: Letter): Pair<PressState, List<Deed>> =
    when (letter) {
        is Letter.Pressed -> press(letter)
        // Экран при открытии — своя машина (openStep); нажатие его не видит.
        is Letter.Opened -> state to emptyList()
        // Анализ подписки — своя машина (scanStep).
        is Letter.Scan, is Letter.ExitSeen -> state to emptyList()
        is Letter.LineObserved -> fed(state, letter, ::onLine)
        is Letter.ProbeAnswered -> fed(state, letter, ::onAnswer)
    }

/**
 * Письмо в машину нажатия: идущее нажатие — в историю и в шаг [run]; кончившееся — только в историю (поздние ответы
 * объясняют, но не меняют итог); до нажатия — мимо.
 */
private fun <L : Letter> fed(
    state: PressState,
    letter: L,
    run: (PressState.Running, L) -> Pair<PressState, List<Deed>>,
): Pair<PressState, List<Deed>> = when (state) {
    is PressState.Running -> run(state.copy(letters = state.letters + letter), letter)
    is PressState.Done -> state.copy(letters = state.letters + letter) to emptyList()
    PressState.Idle -> state to emptyList()
}

private fun press(p: Letter.Pressed): Pair<PressState, List<Deed>> {
    val exit = currentExitOf(p)
    val running = PressState.Running(
        pressed = p, letters = listOf(p), memory = p.memory, queue = emptyList(), inFlight = exit,
        retried = emptySet(), rediagnoses = 0, diagnosis = Diagnosis.Pending,
    )
    val exitProbe = exit.map { Deed.Probe(it.id) }
    return when (val prior = p.freshPrior()) {
        Prior.None -> running to listOf(Deed.ProbeLine) + exitProbe
        is Prior.Detected -> {
            // Линию уже снял детектор при открытии — сценарий сразу, без второго замера.
            val diagnosis = prior.rounds.fold<Letter.LineObserved, Diagnosis>(Diagnosis.Pending) { d, l -> d.observe(p.os, l.line) }
            val (next, deeds) = decide(running.copy(diagnosis = diagnosis), prior.rounds.last().atMs)
            next to (if (next is PressState.Done) deeds else exitProbe + deeds)
        }
    }
}

/**
 * Ключ памяти сети: оператор SIM с данными, «wifi» для Wi-Fi. Общий для машины и свёртки.
 * Подозрение ведёт память как белые списки: какой вход в РФ проходил тогда — лучшая догадка сейчас.
 */
fun memoryKey(os: OsNetwork, scenario: Scenario, service: ServiceId): MemoryKey =
    MemoryKey(
        operator = memoryOperator(os),
        scenario = if (scenario == Scenario.SuspectWhitelist) Scenario.MobileWhitelist else scenario,
        service = service,
    )

/** Сеть в ключе памяти: оператор SIM с данными, «wifi» для Wi-Fi, «none» без сети. */
fun memoryOperator(os: OsNetwork): String = when (os) {
    is OsNetwork.Cellular -> os.operator
    OsNetwork.Wifi -> "wifi"
    OsNetwork.None -> "none"
}

private fun PressState.Running.key(scenario: Scenario): MemoryKey = memoryKey(pressed.os, scenario, pressed.service)

private fun PressState.Running.answered(): Set<ServerId> =
    letters.filterIsInstance<Letter.ProbeAnswered>().map { it.server }.toSet()

/** Раунд в диагноз; поздний раунд при готовом диагнозе только ложится в историю. */
private fun onLine(s: PressState.Running, l: Letter.LineObserved): Pair<PressState, List<Deed>> = when (s.diagnosis) {
    Diagnosis.Pending, is Diagnosis.Suspected, is Diagnosis.Rechecking -> decide(s.copy(diagnosis = s.diagnosis.observe(s.pressed.os, l.line)), l.atMs)
    is Diagnosis.Settled -> s to emptyList()
}

/** Текущий выход из подписки — первым в полёт; выхода нет или его нет в подписке — пусто. */
private fun currentExitOf(p: Letter.Pressed): List<Server> = when (val id = p.currentExit) {
    null -> emptyList()
    else -> p.servers.filter { it.id == id }.take(1)
}

/**
 * Сценарий по диагнозу и очередь под него. Подозрение не ждёт подтверждения: входы в РФ
 * годны при обеих гипотезах — их перебор идёт, пока снимается второй раунд; зарубежные ждут итога.
 */
private fun decide(s: PressState.Running, atMs: Long): Pair<PressState, List<Deed>> {
    // decide зовут только после observe, а он Pending не возвращает.
    val scenario = when (val scene = s.diagnosis.scene) {
        Scene.Pending -> return s to emptyList()
        is Scene.Chosen -> scene.scenario
    }
    if (scenario == Scenario.Offline) return PressState.Done(s.letters, s.diagnosis) to listOf(Deed.Remember(s.memory))
    // Отложенный повтор переживает перепроверку линии: сервер ответил один раз и ждёт второго шанса.
    val answers = s.letters.filterIsInstance<Letter.ProbeAnswered>().groupingBy { it.server }.eachCount()
    val pendingRetries = s.retried.filter { answers[it] == 1 }.toSet()
    val skip = (s.answered() - pendingRetries) + s.inFlight.map { it.id }
    val queue = plan(scenario, s.pressed.servers, s.memory, s.key(scenario), atMs, s.pressed.hints).queue.filter { it.id !in skip }
    val (next, deeds) = launch(s.copy(queue = queue))
    return next to deeds + listOfNotNull(Deed.ProbeLine.takeIf { s.diagnosis is Diagnosis.Suspected })
}

private fun onAnswer(s: PressState.Running, a: Letter.ProbeAnswered): Pair<PressState, List<Deed>> {
    val server = s.inFlight.find { it.id == a.server }
    if (server == null) return s to emptyList()
    val after = s.copy(inFlight = s.inFlight - server)
    return when (a.verdict.kind()) {
        VerdictKind.Fits -> found(after, server, a.atMs)
        // Тишину даёт и холодный старт соединения: блок — только по второй.
        VerdictKind.TlsSilence ->
            if (server.id in s.retried) failed(silenced(after, server, a.atMs)) else failed(retry(after, server))
        VerdictKind.ServiceDead -> if (server.id in s.retried) failed(after) else failed(retry(after, server))
        // TCP до сервера не прошёл — при белых списках это подпись «адрес не в списке», повтор только тратит время.
        VerdictKind.Unreachable -> failed(after)
    }
}

/** Неоднозначный провал — в конец очереди, один раз. */
private fun retry(s: PressState.Running, server: Server): PressState.Running =
    s.copy(queue = s.queue + server, retried = s.retried + server.id)

private fun found(s: PressState.Running, server: Server, atMs: Long): Pair<PressState, List<Deed>> {
    val memory = s.diagnosis.scene.fold({ s.memory }) { s.memory.rememberSuccess(s.key(it), server, atMs) }
    val exit = if (server.id == s.pressed.currentExit && s.pressed.pinned) emptyList() else listOf(Deed.MakeExit(server.id))
    return PressState.Done(s.letters, s.diagnosis) to exit + Deed.Remember(memory) + Deed.MeasureSpeed(server.id)
}

/** TlsSilence: IP:порт под блок; другой порт того же хоста — следующим. */
private fun silenced(s: PressState.Running, server: Server, atMs: Long): PressState.Running {
    val rest = s.queue.filter { it.endpoint != server.endpoint }
    val (sibling, other) = rest.partition { it.endpoint.host == server.endpoint.host }
    return s.copy(memory = s.memory.block(server.endpoint, atMs), queue = sibling + other)
}

private fun failed(s: PressState.Running): Pair<PressState, List<Deed>> = when (val d = s.diagnosis) {
    is Diagnosis.Settled ->
        if (s.rediagnoses < MAX_REDIAGNOSES && watchesForWhitelist(d.scenario) && allDiedAtOnce(s)) {
            s.copy(diagnosis = Diagnosis.Rechecking(d), rediagnoses = s.rediagnoses + 1) to listOf(Deed.ProbeLine)
        } else {
            launch(s)
        }
    // Линию и так снимают: первый раунд, подтверждающий или новый после перепроверки ещё не пришёл.
    Diagnosis.Pending, is Diagnosis.Suspected, is Diagnosis.Rechecking -> launch(s)
}

/**
 * Перепроверка ловит уход в белые списки посреди перебора. В белых списках
 * «все Ru упали» — обычное дело, а пауза на линию съедает бюджет 10 с.
 */
private fun watchesForWhitelist(scenario: Scenario): Boolean = when (scenario) {
    Scenario.MobileOpen, Scenario.Wifi, Scenario.Unclear -> true
    // Итог «подозрение» после непонятного второго раунда перебирает как белые списки.
    Scenario.MobileWhitelist, Scenario.SuspectWhitelist, Scenario.Offline -> false
}

/** Провалы после последнего снятия линии: ≥ 4 на ≥ 4 разных IP (успеха быть не может — иначе уже Done). */
private fun allDiedAtOnce(s: PressState.Running): Boolean {
    val sinceLine = s.letters.takeLastWhile { it !is Letter.LineObserved }.filterIsInstance<Letter.ProbeAnswered>()
    val hosts = sinceLine.mapNotNull { a -> s.pressed.servers.find { it.id == a.server } }.map { it.endpoint.host }.toSet()
    return sinceLine.size >= REDIAGNOSE_AFTER_FAILURES && hosts.size >= REDIAGNOSE_AFTER_FAILURES
}

/** Добрать пробы по бюджету; пусто и в полёте пусто — нажатие кончилось без выхода. */
private fun launch(s: PressState.Running): Pair<PressState, List<Deed>> {
    // Ждём раунд линии — новых проб нет; в полёте доживают свои.
    when (s.diagnosis) {
        Diagnosis.Pending, is Diagnosis.Rechecking -> return s to emptyList()
        is Diagnosis.Suspected, is Diagnosis.Settled -> Unit
    }
    // Повторное нажатие: сначала текущий выход; подбор — только если он не прошёл.
    if (s.pressed.currentExit != null && s.inFlight.any { it.id == s.pressed.currentExit }) return s to emptyList()
    val start = pickStartable(s.queue, s.inFlight)
    val next = s.copy(queue = s.queue - start.toSet(), inFlight = s.inFlight + start)
    // Пока ждём подтверждающий раунд, пустая очередь — ещё не тупик: открытая сеть вернёт зарубежные.
    return if (next.queue.isEmpty() && next.inFlight.isEmpty() && next.diagnosis !is Diagnosis.Suspected) {
        PressState.Done(next.letters, next.diagnosis) to listOf(Deed.Remember(next.memory))
    } else {
        next to start.map { Deed.Probe(it.id) }
    }
}
