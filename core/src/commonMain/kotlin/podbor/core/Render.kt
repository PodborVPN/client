package podbor.core

/** Ready — линия ответила, можно подбирать (экран при открытии). */
enum class Button { Idle, Ready, Searching, Green, Red }

/** Карточка сети: пока линия молчит — Measuring (только то, что знает ОС), потом Measured. Вышку и сигнал добавит край (план 2). */
sealed interface NetworkCard {
    /** Сеть ОС — есть у карточки в любом состоянии замера. */
    val os: OsNetwork

    data class Measuring(override val os: OsNetwork) : NetworkCard
    data class Measured(override val os: OsNetwork, val scenario: Scenario, val evidence: String) : NetworkCard
}

/** Кто виноват, что текущий выход мёртв, — по диагнозу линии. Unexplained: сеть непонятная или линия не ответила к сроку. */
enum class Blame { Whitelist, SuspectedWhitelist, NoNetwork, Server, Unexplained }

/** Откуда выход, на котором сидит человек, и что с ним. Экран при открытии. */
data class ExitRef(val name: String, val transport: Transport)

sealed interface ExitState {
    data object Off : ExitState
    data class Unlisted(val tag: ServerId) : ExitState
    data class Checking(val ref: ExitRef) : ExitState
    data class Works(val ref: ExitRef) : ExitState
    data class Dead(val ref: ExitRef, val blame: Blame) : ExitState
}

sealed interface ResultCard {
    data object Empty : ResultCard
    data class Found(val serverName: String, val transport: Transport, val probedAtMs: Long, val coverage: String) : ResultCard
    data class NotFound(val lines: List<String>) : ResultCard
    /** Экран при открытии: текущий выход и, если мёртв, кто виноват. */
    data class Exit(val state: ExitState) : ResultCard
}

/** [stopwatch] — секундомер нажатия; у экрана открытия его нет. */
data class Screen(val network: NetworkCard, val button: Button, val result: ResultCard, val stopwatch: Stopwatch = Stopwatch.None)

/**
 * Экран — производная истории: render(letters) = f(explain(letters)). Отдельного «состояния сообщений» нет.
 * [closedAtMs] — когда вышел срок трубопровода (край; не вышел — null): нажатие, не дошедшее до итога, — «не найдено»,
 * а не вечное «ищу», и секундомер замирает на сроке — письма в этот момент нет.
 */
fun render(letters: List<Letter>, service: Service = Telegram, closedAtMs: Long? = null): Screen {
    val closed = closedAtMs != null
    val last = letters.lastOrNull { it is Letter.Pressed || it is Letter.Opened }
    // История без края: срока у неё нет, замер не закрыт.
    if (last is Letter.Opened) return renderOpened(openStateFrom(letters, last), closed = false, service = service)
    val pressed = last as? Letter.Pressed
        ?: return Screen(NetworkCard.Measuring(OsNetwork.None), Button.Idle, ResultCard.Empty)
    val press = letters.drop(letters.lastIndexOf(pressed))
    val e = explain(letters)
    // Кончившееся нажатие подтверждающего раунда уже не ждёт: подозрение на экране — без «проверяю».
    val ended = closed || when (e.outcome) {
        Outcome.InProgress -> false
        is Outcome.Found, is Outcome.NotFound -> true
    }
    val card = networkCardOf(pressed.os, e.diagnosis, ended)
    // Кончилось раньше срока — на письме итога; срок вышел посреди перебора — на сроке.
    val stopwatch = when (val end = e.endedAtMs ?: closedAtMs) {
        null -> Stopwatch.Running(pressed.atMs)
        else -> Stopwatch.Stopped(pressed.atMs, end)
    }
    return when (val o = e.outcome) {
        Outcome.InProgress ->
            if (closed) {
                Screen(card, Button.Red, ResultCard.NotFound(words(e, pressed.servers, service) + timedOutWords(service)), stopwatch)
            } else {
                Screen(card, Button.Searching, ResultCard.Empty, stopwatch)
            }
        is Outcome.Found -> {
            val server = pressed.servers.first { it.id == o.server }
            val at = press.filterIsInstance<Letter.ProbeAnswered>().first { it.server == o.server && it.verdict.kind() == VerdictKind.Fits }.atMs
            val coverage = if (o.server == pressed.currentExit) "текущий выход работает" else coverageOf(e.diagnosis.scenario, o)
            Screen(card, Button.Green, ResultCard.Found(server.name, server.transport, at, coverage), stopwatch)
        }
        is Outcome.NotFound -> Screen(card, Button.Red, ResultCard.NotFound(words(e, pressed.servers, service)), stopwatch)
    }
}

private fun timedOutWords(service: Service): String = "${service.title}: за отведённое время рабочий сервер не нашёлся — нажми ещё раз."

/** Слова тупика — свёртка объяснения: что за сеть, что пробовали и как упало, что не трогали, вывод, что осталось. */
fun words(explanation: Explanation, servers: List<Server>, service: Service = Telegram): List<String> {
    val scenario = explanation.diagnosis.scenario
    // При подозрении перебор тот же, что при белых списках: входы в РФ, зарубежные ждут.
    val whitelist = scenario == Scenario.MobileWhitelist || scenario == Scenario.SuspectWhitelist
    val header = listOfNotNull(
        scenario?.let { s -> titleOf(s) + (explanation.lineEvidence?.let { ": " + evidenceOf(it) } ?: "") + "." },
    )
    val triedTotal = explanation.tried.values.sumOf { it.values.sum() }
    val intro = when {
        triedTotal == 0 -> emptyList()
        whitelist -> {
            val ru = explanation.tried.filterKeys { it != CandidateClass.Foreign }.values.sumOf { it.values.sum() }
            listOf("Проверил всё, что в твоём конфиге может пройти белые списки, — $ru ${plural(ru, "вход", "входа", "входов")} в РФ:")
        }
        else -> listOf("Проверил $triedTotal ${plural(triedTotal, "сервер", "сервера", "серверов")}:")
    }
    // Внутри класса — от самой частой подписи провала к редкой.
    val triedLines = CandidateClass.entries.flatMap { cls ->
        explanation.tried[cls].orEmpty().entries
            .sortedWith(compareByDescending<Map.Entry<VerdictKind, Int>> { it.value }.thenBy { it.key.ordinal })
            .map { (kind, n) -> "$n ${nounOf(cls, n)} — ${phraseOf(kind, whitelist, service)}" }
    }
    val skippedLines = CandidateClass.entries.flatMap { cls ->
        explanation.skipped[cls].orEmpty().map { (reason, n) ->
            when (reason) {
                SkipReason.ForeignInWhitelist ->
                    "Остальные $n ${plural(n, "сервер", "сервера", "серверов")} — зарубежные, при белых списках недостижимы, не трогал."
                SkipReason.BlockedRecently -> "$n ${nounOf(cls, n)} — недавно замолчали, не трогаю 10 минут"
                SkipReason.ForeignWhileSuspected ->
                    "Остальные $n ${plural(n, "сервер", "сервера", "серверов")} — зарубежные, отложил: похоже на белые списки, а при них они недостижимы."
            }
        }
    }
    val conclusion = (explanation.outcome as? Outcome.NotFound)?.conclusion
    val verdict = listOfNotNull(conclusion?.let { conclusionOf(it, scenario) })
    val tail = when (conclusion) {
        null, Conclusion.NoNetwork -> emptyList()
        Conclusion.NoAdmissibleInConfig, Conclusion.AllAdmissibleFailed, Conclusion.NothingWorksOpen -> listOf(
            "Российские сайты работают напрямую." +
                if (whitelist) " Нужен сервер с входом в РФ из белого списка — спроси у своего VPN-сервиса." else "",
        )
    }
    return header + intro + triedLines + skippedLines + verdict + tail
}

fun operatorOf(os: OsNetwork): String? = when (os) {
    is OsNetwork.Cellular -> os.operator
    OsNetwork.Wifi -> "Wi-Fi"
    OsNetwork.None -> null
}

/** Строка про вышку для экрана; `null` — показывать нечего (вышка неизвестна или сеть не сотовая). */
fun towerLine(os: OsNetwork): String? = when (os) {
    is OsNetwork.Cellular -> when (val tower = os.tower) {
        Tower.Unknown -> null
        is Tower.Known -> "вышка ${tower.earfcn} \u00b7 \u2212${-tower.rsrpDbm} дБм"
    }
    OsNetwork.Wifi, OsNetwork.None -> null
}

/*
 * Слова экрана — из ядра: экран раскладывает их по строкам и красит.
 */

/** Транспорт словами экрана. */
fun transportWords(t: Transport): String = when (t) {
    Transport.RealityVision -> "Reality"
    Transport.Xhttp -> "XHTTP"
    Transport.Grpc -> "gRPC"
    Transport.Ws -> "WS"
    is Transport.Other -> t.name
}

/** Заголовок карточки сети: пока линия молчит — «измеряю сеть…» (Wi-Fi — так и называем), потом сценарий. */
fun networkTitle(card: NetworkCard): String = when (card) {
    is NetworkCard.Measuring -> when (card.os) {
        OsNetwork.Wifi -> "измеряю Wi-Fi…"
        is OsNetwork.Cellular, OsNetwork.None -> "измеряю сеть…"
    }
    is NetworkCard.Measured -> titleOf(card.scenario)
}

/**
 * Что за выход: имя и транспорт; не из подписки — тег. Off — выход не выбран (до первого нажатия): туннель при этом
 * может работать, сервер выбирает балансер Xray — поэтому не «выключен».
 */
fun exitHead(e: ExitState): String = when (e) {
    ExitState.Off -> "выход не выбран"
    is ExitState.Unlisted -> e.tag.tag
    is ExitState.Checking -> refWords(e.ref.name, e.ref.transport)
    is ExitState.Works -> refWords(e.ref.name, e.ref.transport)
    is ExitState.Dead -> refWords(e.ref.name, e.ref.transport)
}

/** Пояснение к выходу второй строкой экрана; `null` — пояснять нечего (выход работает). */
fun exitNote(e: ExitState, service: Service = Telegram): String? = when (e) {
    ExitState.Off -> "Xray выбирает сам — нажми «Подобрать»"
    is ExitState.Works -> null
    is ExitState.Unlisted -> "такого сервера нет в подписке, проверить нечем"
    is ExitState.Checking -> "проверяю ${service.titleAcc}…"
    is ExitState.Dead -> when (e.blame) {
        Blame.Whitelist -> "белые списки: сеть его не пропускает"
        Blame.SuspectedWhitelist -> "похоже на белые списки, подтверждаю"
        Blame.NoNetwork -> "нет сети"
        Blame.Server -> "сеть ни при чём — проблема у сервера"
        Blame.Unexplained -> "сеть непонятная, причину не называю"
    }
}

/** Выход по имени и транспорту: «NL-7» (gRPC); транспорт, уже названный в имени целым словом, не повторяется. */
fun refWords(name: String, transport: Transport): String {
    val word = transportWords(transport)
    val named = Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(word)}([^\\p{L}\\p{N}]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)
    return if (named) "«$name»" else "«$name» ($word)"
}

private fun titleOf(s: Scenario): String = when (s) {
    Scenario.MobileWhitelist -> "Белые списки"
    Scenario.SuspectWhitelist -> "Похоже на белые списки"
    Scenario.MobileOpen -> "Сеть чистая"
    Scenario.Wifi -> "Wi-Fi"
    Scenario.Offline -> "Нет сети"
    Scenario.Unclear -> "Сеть непонятная"
}

/**
 * Карточка сети из диагноза: подозрение дописывает «проверяю ещё раз», пока второй раунд ждут;
 * closed — срок вышел, ждать нечего.
 */
internal fun networkCardOf(os: OsNetwork, diagnosis: Diagnosis, closed: Boolean): NetworkCard = when (diagnosis) {
    Diagnosis.Pending -> NetworkCard.Measuring(os)
    is Diagnosis.Suspected -> {
        val words = evidenceOf(diagnosis.first)
        NetworkCard.Measured(os, Scenario.SuspectWhitelist, if (closed) words else "$words — проверяю ещё раз")
    }
    is Diagnosis.Settled -> NetworkCard.Measured(os, diagnosis.scenario, evidenceOf(diagnosis.rounds.last()))
    is Diagnosis.Rechecking -> networkCardOf(os, diagnosis.previous, closed)
}

/** Улика раунда словами. */
internal fun evidenceOf(line: LineObservation): String {
    fun ok(side: Nel<Sample>) = side.count { it.handshake is Handshake.Ok }
    return when (roundOf(line)) {
        RoundKind.Open -> "заграница отвечает"
        RoundKind.WhitelistLike -> "заграница не отвечает, РФ работает"
        RoundKind.Offline -> "ни РФ, ни заграница не отвечают"
        RoundKind.Unclear -> "ответы неполные: РФ ${ok(line.russia)} из ${line.russia.size}, заграница ${ok(line.abroad)} из ${line.abroad.size}"
    }
}

private fun nounOf(cls: CandidateClass, n: Int): String = when (cls) {
    CandidateClass.RuEntry -> plural(n, "вход", "входа", "входов") + " в РФ"
    CandidateClass.Foreign -> plural(n, "зарубежный", "зарубежных", "зарубежных")
    CandidateClass.UnknownEntry -> plural(n, "сервер", "сервера", "серверов") + " с неизвестным входом"
}

private fun phraseOf(kind: VerdictKind, whitelist: Boolean, service: Service): String = when (kind) {
    VerdictKind.TlsSilence -> "тишина после рукопожатия: оператор режет эти адреса"
    VerdictKind.Unreachable -> if (whitelist) "не отвечают: их адресов нет в белом списке" else "не отвечают"
    VerdictKind.ServiceDead -> "соединился, но ${service.title} не ответила"
    VerdictKind.Fits -> "${service.title} работает"
}

private fun conclusionOf(c: Conclusion, scenario: Scenario?): String = when (c) {
    Conclusion.AllAdmissibleFailed ->
        if (scenario == Scenario.SuspectWhitelist) "Похоже на белые списки, и ни один вход в РФ сейчас не проходит." else "С этим конфигом белые списки не пробить."
    Conclusion.NoAdmissibleInConfig -> "В твоём конфиге нет ни одного входа в РФ."
    Conclusion.NoNetwork -> "Нет сети."
    Conclusion.NothingWorksOpen -> "У провайдера сейчас не работает ни один сервер."
}

/** «проверено K из N входов РФ» — родительный после «из»: 1 входа, 21 входа, остальные — входов. */
private fun coverageOf(scenario: Scenario?, o: Outcome.Found): String {
    val genitiveOne = o.admissible % 10 == 1 && o.admissible % 100 != 11
    return when (scenario) {
        // Повторное нажатие: текущий выход подтвердился раньше, чем пришла линия.
        null -> "текущий выход работает"
        Scenario.MobileWhitelist, Scenario.SuspectWhitelist -> "проверено ${o.position} из ${o.admissible} ${if (genitiveOne) "входа" else "входов"} РФ"
        Scenario.MobileOpen, Scenario.Wifi, Scenario.Unclear, Scenario.Offline ->
            "проверено ${o.position} из ${o.admissible} ${if (genitiveOne) "сервера" else "серверов"}"
    }
}

/** Русское согласование с числом: 1 сервер, 2 сервера, 5 серверов, 11 серверов, 21 сервер. */
fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 100 in 11..14 -> many
    n % 10 == 1 -> one
    n % 10 in 2..4 -> few
    else -> many
}
