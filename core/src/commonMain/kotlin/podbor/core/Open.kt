package podbor.core

/** Сколько живёт линия детектора: нажатие в этот срок после открытия не меряет её заново. */
const val LINE_FRESH_MS = 30_000L

/** Состояние экрана при открытии: что за диагноз у линии и что ответил текущий выход. Флагов фазы нет. */
data class OpenState(val opened: Letter.Opened, val diagnosis: Diagnosis, val exitAnswers: List<Verdict>)

/**
 * Детектор при открытии экрана: разовый замер, без перебора. Линия напрямую говорит, что за сеть;
 * проба текущего выхода — жив ли он. Выход не из подписки (Unlisted) пробовать нечем — только линия.
 */
fun openStart(o: Letter.Opened): Pair<OpenState, List<Deed>> {
    val probe = when (val vpn = o.vpn) {
        is Vpn.On -> listOf(Deed.Probe(vpn.exit.id))
        Vpn.Off, is Vpn.Unlisted -> emptyList()
    }
    return OpenState(o, Diagnosis.Pending, emptyList()) to (listOf(Deed.ProbeLine) + probe)
}

fun openStep(s: OpenState, letter: Letter): Pair<OpenState, List<Deed>> = when (letter) {
    // Подозрение на белые списки — второй раунд сразу: «белые списки» только по двум подряд.
    is Letter.LineObserved -> {
        val next = s.diagnosis.observe(s.opened.os, letter.line)
        s.copy(diagnosis = next) to if (s.diagnosis !is Diagnosis.Suspected && next is Diagnosis.Suspected) listOf(Deed.ProbeLine) else emptyList()
    }
    is Letter.ProbeAnswered -> when (val vpn = s.opened.vpn) {
        is Vpn.On -> if (letter.server == vpn.exit.id) {
            // Одна тишина или мёртвая Телега — ещё не приговор: повтор один раз. Unreachable не повторяем.
            val ambiguous = letter.verdict.kind() == VerdictKind.TlsSilence || letter.verdict.kind() == VerdictKind.ServiceDead
            s.copy(exitAnswers = s.exitAnswers + letter.verdict) to
                if (ambiguous && s.exitAnswers.isEmpty()) listOf(Deed.Probe(vpn.exit.id)) else emptyList()
        } else {
            s to emptyList()
        }
        Vpn.Off, is Vpn.Unlisted -> s to emptyList()
    }
    // Открытие начинается openStart; нажатие — другая машина.
    is Letter.Opened, is Letter.Pressed -> s to emptyList()
    // Анализ подписки — своя машина (scanStep).
    is Letter.Scan, is Letter.ExitSeen -> s to emptyList()
}

/** Раунды детектора, ещё годные для нажатия (по последнему); иначе None — нажатие меряет само. */
fun Letter.Pressed.freshPrior(): Prior = prior.freshAt(atMs)

/** Раунды, годные в момент [atMs] (по последнему); иначе None. */
fun Prior.freshAt(atMs: Long): Prior = when (this) {
    Prior.None -> Prior.None
    is Prior.Detected -> if (atMs - rounds.last().atMs in 0..LINE_FRESH_MS) this else Prior.None
}

/** Замер кончен: линия дала окончательный диагноз и выход (если он есть в подписке) получил окончательный ответ. */
fun OpenState.settled(): Boolean = diagnosis is Diagnosis.Settled && when (opened.vpn) {
    is Vpn.On -> lifeOf(exitAnswers) != Life.Checking
    Vpn.Off, is Vpn.Unlisted -> true
}

/** История букв после последнего Opened, свёрнутая машиной открытия; null — открытия в истории нет. */
internal fun openStateOf(letters: List<Letter>): OpenState? =
    (letters.lastOrNull { it is Letter.Opened } as? Letter.Opened)?.let { openStateFrom(letters, it) }

/** Состояние открытия от уже найденного письма Opened — тотально: искать нечего, значит и null неоткуда. */
internal fun openStateFrom(letters: List<Letter>, opened: Letter.Opened): OpenState =
    letters.drop(letters.lastIndexOf(opened) + 1).fold(openStart(opened).first) { s, letter -> openStep(s, letter).first }

/** Жизнь выхода по ответам его пробы; внутренняя — наружу уходит ExitState. */
private enum class Life { Checking, Works, Dead }

/** Fits — сразу; неоднозначный провал ждёт повтора; второй провал или Unreachable — мёртв. */
private fun lifeOf(answers: List<Verdict>): Life {
    val last = answers.lastOrNull()?.kind() ?: return Life.Checking
    return when (last) {
        VerdictKind.Fits -> Life.Works
        VerdictKind.Unreachable -> Life.Dead
        VerdictKind.TlsSilence, VerdictKind.ServiceDead -> if (answers.size >= 2) Life.Dead else Life.Checking
    }
}

/**
 * Экран при открытии: сеть (вход) → кнопка (функтор) → текущий VPN (выход).
 * closed — срок замера вышел (край): подтверждающего раунда уже не ждём, «проверяю» не пишем.
 */
fun renderOpened(s: OpenState, closed: Boolean, service: Service = Telegram): Screen {
    val scenario = s.diagnosis.scenario
    val exit = exitStateOf(s, closed)
    // Выход работает — подбирать нечего, кнопка зелёная; иначе — «Подобрать», если сеть есть.
    val button = when {
        scenario == null || scenario == Scenario.Offline -> Button.Idle
        exit is ExitState.Works -> Button.Green
        else -> Button.Ready
    }
    return Screen(networkCardOf(s.opened.os, s.diagnosis, closed), button, ResultCard.Exit(exit))
}

private fun exitStateOf(s: OpenState, closed: Boolean): ExitState = when (val vpn = s.opened.vpn) {
    Vpn.Off -> ExitState.Off
    is Vpn.Unlisted -> ExitState.Unlisted(vpn.tag)
    is Vpn.On -> {
        val ref = ExitRef(vpn.exit.name, vpn.exit.transport)
        val relocation = relocationOf(vpn.chosenOn, s.opened.os)
        when (lifeOf(s.exitAnswers)) {
            Life.Checking -> ExitState.Checking(ref, relocation)
            Life.Works -> ExitState.Works(ref, relocation)
            // Линия ещё молчит — причину не назвать: до срока ждём, по сроку — «не объяснено».
            Life.Dead -> when (val sc = s.diagnosis.scenario) {
                null -> if (closed) ExitState.Dead(ref, Blame.Unexplained, relocation) else ExitState.Checking(ref, relocation)
                else -> ExitState.Dead(ref, blameOf(sc), relocation)
            }
        }
    }
}

private fun blameOf(s: Scenario): Blame = when (s) {
    Scenario.MobileWhitelist -> Blame.Whitelist
    Scenario.SuspectWhitelist -> Blame.SuspectedWhitelist
    Scenario.Offline -> Blame.NoNetwork
    Scenario.MobileOpen, Scenario.Wifi -> Blame.Server
    Scenario.Unclear -> Blame.Unexplained
}
