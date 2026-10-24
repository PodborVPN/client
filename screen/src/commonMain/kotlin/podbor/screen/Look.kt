package podbor.screen

import podbor.core.Blame
import podbor.core.Since
import podbor.core.Board
import podbor.core.Button
import podbor.core.Relocation
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.ExitState
import podbor.core.HARDCODED_SERVICES
import podbor.core.NetworkCard
import podbor.core.PairPulse
import podbor.core.PulseView
import podbor.core.Scenario
import podbor.core.Service
import podbor.core.ServiceCard
import podbor.core.ServiceId
import podbor.core.Stopwatch
import podbor.core.Traffic
import podbor.core.networkKey
import podbor.core.exitHead
import podbor.core.exitNotes
import podbor.core.transportWords

/*
 * Вид экрана A без Compose (JVM-тесты): цвет по смыслу, щит кнопки, строки блока «текущий VPN».
 * Слова о выходе — из ядра; слова о связи с VPN — экранные.
 */

/** Смысловой цвет: Ok — зелёный, Warn — янтарный, Fail — красный, Neutral — текст, Muted — серый. */
enum class Tone { Ok, Warn, Fail, Neutral, Muted }

/** Значок над словом кнопки: щит без знака, с галочкой, с крестиком. */
enum class Shield { Plain, Check, Cross }

/** Отметка сервиса в строке: проверяю, работает, не работает. */
enum class Mark { Checking, Works, Dead }

/** Строка под карточкой: слова и цвет точки. */
data class Note(val text: String, val tone: Tone)

/**
 * Строка блока «текущий VPN», ценность слева направо (вариант A): [отметка + метка сервиса] | [имя сервера] | [протокол];
 * под ней — заметки; [searching] — полоска поиска; [retry] — «повторить» (подъём не удался: повтор одним тапом, а не
 * перезапуском приложения); [stopwatch] — секундомер подбора этого сервиса; [pulse] — величины пары сервиса и его
 * сервера ([pulseLine]), пока у строки есть сервер. Строка связи с VPN — без сервиса: [label] пуст.
 */
data class ServiceRow(
    val label: String,
    val mark: Mark?,
    val name: String,
    val protocol: String?,
    val notes: List<Note>,
    val searching: Boolean,
    val retry: Boolean = false,
    val stopwatch: Stopwatch = Stopwatch.None,
    val pulse: String? = null,
    /** Мини-статус — вторая строка строки сервиса ([miniOf]); у строки связи с VPN — её первая заметка. */
    val mini: Note = firstNote(notes),
)

private fun firstNote(notes: List<Note>): Note = when (val n = notes.firstOrNull()) {
    null -> Note("", Tone.Muted)
    else -> n
}

fun toneOf(card: NetworkCard): Tone = when (card) {
    is NetworkCard.Measuring -> Tone.Muted
    is NetworkCard.Measured -> toneOf(card.scenario)
}

fun toneOf(scenario: Scenario): Tone = when (scenario) {
    Scenario.MobileWhitelist, Scenario.SuspectWhitelist -> Tone.Warn
    Scenario.MobileOpen, Scenario.Wifi -> Tone.Ok
    Scenario.Offline -> Tone.Fail
    Scenario.Unclear -> Tone.Neutral
}

fun toneOf(button: Button): Tone = when (button) {
    Button.Idle -> Tone.Muted
    Button.Ready, Button.Searching -> Tone.Warn
    Button.Green -> Tone.Ok
    Button.Red -> Tone.Fail
}

fun shieldOf(button: Button): Shield = when (button) {
    Button.Idle, Button.Ready, Button.Searching -> Shield.Plain
    Button.Green -> Shield.Check
    Button.Red -> Shield.Cross
}

private fun plain(name: String, vararg notes: Note) = ServiceRow("", null, name, null, notes.toList(), searching = false)

/**
 * Строки блока: без поднятого VPN — одна строка его состояния (устаревший результат не показывается), иначе — строка на
 * сервис по [ServiceId]. [origin] — чей выход восстановлен из памяти сети: пока его проверяют, так и говорим.
 * [services] — описания сервисов для слов («проверяю Инсту…»); [pulse] — величины пар сессии.
 */
fun rowsOf(
    board: Board,
    link: Link,
    origin: Map<ServiceId, ExitOrigin>,
    services: Set<Service> = HARDCODED_SERVICES,
    pulse: PulseView = PulseView.Empty,
): List<ServiceRow> = when (link) {
    Link.Starting -> listOf(plain("поднимаю VPN…"))
    Link.Consent -> listOf(plain("жду разрешения на VPN…", Note("подтверди запрос системы", Tone.Warn)))
    Link.Refused -> listOf(
        plain(
            "VPN не разрешён",
            Note("без разрешения подбирать нечем", Tone.Fail),
            // Постоянный VPN другого приложения Android отклоняет запрос сам, без диалога.
            Note("если включён постоянный VPN другого приложения — выключи его в настройках Android", Tone.Muted),
        ),
    )
    Link.Failed -> listOf(plain("VPN не поднялся").copy(retry = true))
    Link.Off -> listOf(plain("VPN выключен", Note("нажми «Включить»", Tone.Muted)))
    Link.Up -> {
        val cards = board.cards.sortedBy { it.service.id }
        // Не нашли несколько сервисов — первая строка explain (что за сеть) у них общая: один раз, у первого.
        val lost = cards.filter { it.status is CardStatus.NotFound }
        val shared = lost.map { it.status }.filterIsInstance<CardStatus.NotFound>().take(1).flatMap { it.lines.take(1) }
        cards.map { card ->
            val st = card.status
            val repeats = st is CardStatus.NotFound && card != lost.first() && st.lines.take(1) == shared
            val from = origin.getOrElse(card.service) { ExitOrigin.Chosen }
            val pair = pulse.pairs.getOrElse(card.service) { PairPulse.Unseen }
            rowOf(card, services.find { it.id == card.service }, from, repeats)
                .copy(stopwatch = card.stopwatch, pulse = pulseOf(card.status, pair), mini = miniOf(card.status, from, pair))
        }
    }
}

/**
 * [service] — описание сервиса карточки; нет — строка с меткой карточки, без слов сервиса («проверяю…»).
 * [sharedHead] — первая строка тупика уже показана у другого сервиса: здесь её нет.
 */
private fun rowOf(card: ServiceCard, service: Service?, origin: ExitOrigin, sharedHead: Boolean = false): ServiceRow {
    fun server(mark: Mark, ref: ExitRef, notes: List<Note> = emptyList()) =
        ServiceRow(card.label, mark, ref.name, transportWords(ref.transport), notes, searching = false)
    fun bare(name: String, notes: List<String>) =
        ServiceRow(card.label, null, name, null, notes.map { Note(it, Tone.Muted) }, searching = false)
    return when (val st = card.status) {
        // Ничего не известно — балансер выбирает сам: слова прежнего «выход не выбран».
        // Слова Off, Unlisted и Dead от сервиса не зависят — только «проверяю <сервис>…».
        CardStatus.Unknown -> bare(exitHead(ExitState.Off), exitNotes(ExitState.Off))
        is CardStatus.Unlisted -> ExitState.Unlisted(st.tag).let { bare(exitHead(it), exitNotes(it)) }
        CardStatus.Searching -> ServiceRow(card.label, null, "подбираю выход…", null, emptyList(), searching = true)
        is CardStatus.Checking -> server(Mark.Checking, st.ref, checkingNotes(st, origin, service).map { Note(it, Tone.Warn) })
        is CardStatus.Works -> server(Mark.Works, st.ref, exitNotes(ExitState.Works(st.ref, st.relocation)).map { Note(it, Tone.Ok) })
        // Причина — красным, подсказка после переезда — янтарным: это действие, а не приговор.
        is CardStatus.Dead -> server(
            Mark.Dead, st.ref,
            exitNotes(ExitState.Dead(st.ref, st.blame, st.relocation)).mapIndexed { i, n -> Note(n, if (i == 0) Tone.Fail else Tone.Warn) },
        )
        is CardStatus.Found -> server(Mark.Works, st.ref, listOf(Note(st.coverage, Tone.Ok)))
        // Тупик — словами explain: первая строка (что за сеть) ярче, остальное — серым.
        is CardStatus.NotFound -> ServiceRow(
            card.label, Mark.Dead, "нет рабочего сервера", null,
            st.lines.mapIndexed { i, line -> Note(line, if (i == 0) Tone.Neutral else Tone.Muted) }.drop(if (sharedHead) 1 else 0),
            searching = false,
        )
    }
}

/**
 * Мини-статус строки сервиса — одна короткая фраза: работает ли и что сейчас происходит. Длинные слова (тупик,
 * переезд, причины) — в шторке; фраза от них не зависит, и строка не меняет высоту ни в каком состоянии.
 */
fun miniOf(status: CardStatus, origin: ExitOrigin, pulse: PairPulse): Note = when (status) {
    CardStatus.Unknown -> Note("Xray выбирает сам", Tone.Muted)
    is CardStatus.Unlisted -> Note("нет в подписке", Tone.Muted)
    CardStatus.Searching -> Note("подбираю…", Tone.Warn)
    is CardStatus.Checking -> Note(checkingMini(status.relocation, origin), Tone.Warn)
    is CardStatus.Works -> Note(worksMini(pulse), Tone.Ok)
    is CardStatus.Found -> Note("найден · ${status.coverage}", Tone.Ok)
    is CardStatus.Dead -> Note(deadMini(status.blame), Tone.Fail)
    is CardStatus.NotFound -> Note(status.lines.firstOrNull().orEmpty(), Tone.Fail)
}

/**
 * Заметки проверки выхода: восстановленный из памяти подобран на этой же сети — переезда у него нет, слова свои; без
 * описания сервиса — без его имени.
 */
private fun checkingNotes(st: CardStatus.Checking, origin: ExitOrigin, service: Service?): List<String> = when {
    origin == ExitOrigin.Recalled -> listOf("последний рабочий: ${st.ref.name} — проверяю")
    service == null -> listOf("проверяю…")
    else -> exitNotes(ExitState.Checking(st.ref, st.relocation), service)
}

private fun checkingMini(relocation: Relocation, origin: ExitOrigin): String = when {
    relocation is Relocation.Moved -> "сеть сменилась — проверяю"
    origin == ExitOrigin.Recalled -> "последний рабочий — проверяю"
    else -> "проверяю"
}

private fun worksMini(pulse: PairPulse): String = when (pulse) {
    PairPulse.Unseen -> "работает"
    is PairPulse.Seen -> if (pulse.downBps == 0L && pulse.upBps == 0L) "работает · сейчас не пользуется" else "работает · ↓ ${rateText(pulse.downBps)}"
}

private fun deadMini(blame: Blame): String = when (blame) {
    Blame.Whitelist -> "белые списки"
    Blame.SuspectedWhitelist -> "похоже на белые списки"
    Blame.NoNetwork -> "нет сети"
    Blame.Server -> "сервер не отвечает"
    Blame.Unexplained -> "не отвечает"
}

/** Пульс — у строки с сервером (работает, найден, проверяю); поиск, тупик и прочее — без него: пары нет. */
private fun pulseOf(status: CardStatus, p: PairPulse): String? = when (status) {
    is CardStatus.Works, is CardStatus.Found, is CardStatus.Checking -> pulseLine(p)
    CardStatus.Unknown, is CardStatus.Unlisted, CardStatus.Searching, is CardStatus.Dead, is CardStatus.NotFound -> null
}

/** Время на кнопке — крупно, без долей: «0:08». */
fun buttonTime(ms: Long): String = (ms / 1000).let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" }

/** Время на карточке — с десятыми, пока меньше минуты: «8,3 с»; дальше — как на кнопке. */
fun cardTime(ms: Long): String = if (ms < 60_000) "${ms / 1000},${ms % 1000 / 100} с" else buttonTime(ms)

private val UNITS = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")

/**
 * Байты десятичными единицами, как «Использование данных» Android: с округлением; до 100 — с десятой через запятую
 * (нулевую не пишем: «12 КБ», но «9,8 КБ»), дальше целым; округлилось до 1000 — следующая единица («1 МБ», а не
 * «1000 КБ»).
 */
fun bytesText(n: Long): String {
    if (n < 1_000) return "$n ${UNITS[0]}"
    var k = 1
    var scale = 1_000L
    while (k < UNITS.lastIndex && (n + scale / 2) / scale >= 1_000) {
        k++
        scale *= 1_000
    }
    val tenths = (n * 10 + scale / 2) / scale
    return when {
        tenths < 1_000 && tenths % 10 != 0L -> "${tenths / 10},${tenths % 10} ${UNITS[k]}"
        tenths < 1_000 -> "${tenths / 10} ${UNITS[k]}"
        else -> "${(n + scale / 2) / scale} ${UNITS[k]}"
    }
}

fun rateText(bps: Long): String = "${bytesText(bps)}/с"

/** «ответ 2 с назад», с минуты — «ответ 2 мин назад». */
private fun answeredText(ms: Long): String = if (ms < 60_000) "ответ ${ms / 1_000} с назад" else "ответ ${ms / 60_000} мин назад"

/**
 * Строка пульса пары — только величины, без вердиктов: темп вниз и вверх и сколько назад был ответ. Ни байта в окне —
 * «сейчас не пользуется» (не наблюдали спроса, а не «не работает»); не наблюдали пару вовсе — строки нет.
 */
fun pulseLine(p: PairPulse): String? = when (p) {
    PairPulse.Unseen -> null
    is PairPulse.Seen ->
        if (p.downBps == 0L && p.upBps == 0L) {
            "сейчас не пользуется"
        } else {
            (listOf("↓ ${rateText(p.downBps)}", "↑ ${rateText(p.upBps)}") + answeredWords(p.answered)).joinToString(" · ")
        }
}

private fun answeredWords(s: Since): List<String> = when (s) {
    Since.Never -> emptyList()
    is Since.Ago -> listOf(answeredText(s.ms))
}

/** Трафик сессии: ↓ первым. */
fun trafficLine(t: Traffic): String = "↓ ${bytesText(t.down)} · ↑ ${bytesText(t.up)}"

/**
 * «СЕТЬ» — общий трафик Podbor (приложения через VPN и пробы, анализ тоже) на этой сети: итог показывается, только если
 * его ключ — ключ сети на экране (сеть сменилась, а Xray не запущен, — старый итог чужой сети не показываем).
 */
fun networkTraffic(pulse: PulseView, card: NetworkCard): String? = when (val t = pulse.network) {
    null -> null
    else -> if (t.key == networkKey(card.os)) trafficLine(t.total) else null
}

/** «ТЕКУЩИЙ VPN» — трафик, прошедший через VPN на этой подписке. */
fun vpnTraffic(pulse: PulseView): String? = when (val t = pulse.vpn) {
    null -> null
    else -> trafficLine(t.total)
}
