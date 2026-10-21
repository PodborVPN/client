package podbor.app.ui

import podbor.app.vpn.ExitOrigin
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.ExitState
import podbor.core.HARDCODED_SERVICES
import podbor.core.NetworkCard
import podbor.core.Scenario
import podbor.core.Service
import podbor.core.ServiceCard
import podbor.core.ServiceId
import podbor.core.exitHead
import podbor.core.exitNote
import podbor.core.transportWords

/*
 * Вид экрана A без Compose (JVM-тесты): цвет по смыслу, щит кнопки, строки блока «текущий VPN».
 * Слова о выходе — из ядра; слова о связи с VPN — экранные (их нет в CLI).
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
 * перезапуском приложения). Строка связи с VPN — без сервиса: [label] пуст.
 */
data class ServiceRow(
    val label: String,
    val mark: Mark?,
    val name: String,
    val protocol: String?,
    val notes: List<Note>,
    val searching: Boolean,
    val retry: Boolean = false,
)

fun toneOf(card: NetworkCard): Tone = when (card) {
    is NetworkCard.Measuring -> Tone.Muted
    is NetworkCard.Measured -> when (card.scenario) {
        Scenario.MobileWhitelist, Scenario.SuspectWhitelist -> Tone.Warn
        Scenario.MobileOpen, Scenario.Wifi -> Tone.Ok
        Scenario.Offline -> Tone.Fail
        Scenario.Unclear -> Tone.Neutral
    }
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
 * [services] — описания сервисов для слов («проверяю Инсту…»).
 */
fun rowsOf(
    board: Board,
    link: Link,
    origin: Map<ServiceId, ExitOrigin>,
    services: Set<Service> = HARDCODED_SERVICES,
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
        val shared = (lost.firstOrNull()?.status as? CardStatus.NotFound)?.lines?.firstOrNull()
        cards.map { card ->
            val st = card.status
            val repeats = st is CardStatus.NotFound && card != lost.first() && st.lines.firstOrNull() == shared
            rowOf(card, services.find { it.id == card.service }, origin[card.service] ?: ExitOrigin.Chosen, repeats)
        }
    }
}

/**
 * [service] — описание сервиса карточки; нет — строка с меткой карточки, без слов сервиса («проверяю…»).
 * [sharedHead] — первая строка тупика уже показана у другого сервиса: здесь её нет.
 */
private fun rowOf(card: ServiceCard, service: Service?, origin: ExitOrigin, sharedHead: Boolean = false): ServiceRow {
    fun server(mark: Mark, ref: ExitRef, note: Note? = null) =
        ServiceRow(card.label, mark, ref.name, transportWords(ref.transport), listOfNotNull(note), searching = false)
    fun bare(name: String, note: String?) =
        ServiceRow(card.label, null, name, null, listOfNotNull(note?.let { Note(it, Tone.Muted) }), searching = false)
    return when (val st = card.status) {
        // Ничего не известно — балансер выбирает сам: слова прежнего «выход не выбран».
        // Слова Off, Unlisted и Dead от сервиса не зависят — только «проверяю <сервис>…».
        CardStatus.Unknown -> bare(exitHead(ExitState.Off), exitNote(ExitState.Off))
        is CardStatus.Unlisted -> ExitState.Unlisted(st.tag).let { bare(exitHead(it), exitNote(it)) }
        CardStatus.Searching -> ServiceRow(card.label, null, "подбираю выход…", null, emptyList(), searching = true)
        is CardStatus.Checking -> {
            val note = when (origin) {
                ExitOrigin.Recalled -> "последний рабочий: ${st.ref.name} — проверяю"
                ExitOrigin.Chosen -> service?.let { exitNote(ExitState.Checking(st.ref), it) } ?: "проверяю…"
            }
            server(Mark.Checking, st.ref, Note(note, Tone.Warn))
        }
        is CardStatus.Works -> server(Mark.Works, st.ref)
        is CardStatus.Dead -> server(Mark.Dead, st.ref, exitNote(ExitState.Dead(st.ref, st.blame))?.let { Note(it, Tone.Fail) })
        is CardStatus.Found -> server(Mark.Works, st.ref, Note(st.coverage, Tone.Ok))
        // Тупик — словами explain: первая строка (что за сеть) ярче, остальное — серым.
        is CardStatus.NotFound -> ServiceRow(
            card.label, Mark.Dead, "нет рабочего сервера", null,
            st.lines.mapIndexed { i, line -> Note(line, if (i == 0) Tone.Neutral else Tone.Muted) }.drop(if (sharedHead) 1 else 0),
            searching = false,
        )
    }
}
