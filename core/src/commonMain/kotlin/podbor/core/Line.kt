@file:UseSerializers(NonEmptyListSerializer::class)

package podbor.core

import arrow.core.NonEmptyList
import arrow.core.serialization.NonEmptyListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Непустой список: сторона раунда и сами раунды не бывают пустыми по построению. */
typealias Nel<T> = NonEmptyList<T>

/** На какой фазе рукопожатия кончилось (фазы рукопожатия). */
@Serializable
enum class Stage { Connect, Tls }

/** Исход одного TLS-рукопожатия линии — сырое наблюдение; вердикт выносит ядро (roundOf). */
@Serializable
sealed interface Handshake {
    /** ServerHello и сертификат, какой ждали. connectMs — RTT опоры для порога тишины. */
    @Serializable data class Ok(val connectMs: Long) : Handshake
    /**
     * Ждали waitedMs и не дождались: SYN без ответа (Connect) или ServerHello (Tls).
     * Факты края, чтобы ядро отличало молчание сети от своего бюджета (RU «молчат» ~305 мс — это остаток потолка):
     * allowedMs — сколько край был готов ждать на этой фазе от её начала (min таймаута фазы и остатка потолка);
     * beforeMs — сколько ушло от начала пробы до начала фазы (резолв; для Tls — резолв + connect). null — Лента v0.
     */
    @Serializable data class Silent(
        val stage: Stage,
        val waitedMs: Long,
        val allowedMs: Long? = null,
        val beforeMs: Long? = null,
    ) : Handshake
    @Serializable data class Reset(val stage: Stage) : Handshake
    /** Чужой сертификат — подмена. */
    @Serializable data object Mirage : Handshake
    /** RST на SYN: кто-то ответил — не улика ни за, ни против. */
    @Serializable data object Refused : Handshake
    /** ENETUNREACH/EHOSTUNREACH — маршрута нет. */
    @Serializable data object NoRoute : Handshake
    /** Не знаем, что случилось (DNS, protect, прочее). Незнание не улика. */
    @Serializable data class Unknown(val what: String) : Handshake
}

/**
 * Чей эталон. Public — чужой большой сервис (CDN, DNS-гигант): самые вероятные члены белого списка,
 * поэтому их рукопожатие ещё не значит «открыто». Own — наш собственный эталон (podbor-ref на нашем VPS):
 * в белый список он не попадёт, его закрытость — прямая улика против «открыто».
 * Роль — свойство эталона, а не его адреса: ядро не знает IP и имён, край ставит роль при сборке раунда.
 */
@Serializable
enum class Role { Public, Own }

/** Эталон и его исход. target — публичное имя эталона, не адрес сервера подписки. */
@Serializable
data class Sample(val target: String, val handshake: Handshake, val role: Role = Role.Public)

/** Один раунд линии: прямые TLS-пробы мимо туннеля к российским эталонам (опора) и к загранице. */
@Serializable
data class LineObservation(val russia: Nel<Sample>, val abroad: Nel<Sample>)

enum class RoundKind { Open, WhitelistLike, Offline, Unclear }

/** Тишина — улика, только если ждали ≥ 10×RTT опоры и ≥ 300 мс: иначе это медленная сеть (порог HelloMuted). */
private const val SILENCE_FLOOR_MS = 300L
private const val SILENCE_RTT_FACTOR = 10

/**
 * Потолок порога тишины. Край ждёт сам не дольше connect 1500 мс и всей пробы 1800 мс (ServerHello — остаток потолка);
 * порог выше этих ожиданий недостижим, и при мобильном RTT ≥ 150 мс белые списки стали бы неразличимы (раунд всегда Unclear).
 */
private const val SILENCE_FLOOR_CAP_MS = 1_000L

/** Кворум стороны: 2 из 3. */
private const val QUORUM = 2

/**
 * Вердикт одного раунда: заграница с рукопожатием — открыто; опора жива, заграница закрыта — похоже на белые списки.
 *
 * Свой эталон (Role.Own) закрыт — «открыто» запрещено: CDN и DNS-гиганты — первые кандидаты в белый список,
 * и два их рукопожатия при молчащем собственном VPS — подпись белого списка, а не открытой сети. Ложное «открыто» —
 * худшая ошибка (перебор уйдёт в зарубежные, которые недостижимы). Unknown своего эталона (DNS, VPS лежит) — не улика.
 *
 * «Нет сети» — только если обе стороны показали Silent(Connect)/NoRoute и никто ничем не ответил. Unknown не считается
 * ни ответом, ни молчанием: отказ DNS (Private DNS, DNS через мёртвый туннель) не должен гасить кнопку.
 */
fun roundOf(line: LineObservation): RoundKind {
    val ruOk = line.russia.map { it.handshake }.filterIsInstance<Handshake.Ok>()
    val farOk = line.abroad.count { it.handshake is Handshake.Ok }
    val rtt = ruOk.map { it.connectMs }.sorted().getOrNull(ruOk.size / 2) ?: 0
    val floor = minOf(maxOf(SILENCE_RTT_FACTOR * rtt, SILENCE_FLOOR_MS), SILENCE_FLOOR_CAP_MS)
    fun closed(h: Handshake): Boolean = when (h) {
        is Handshake.Silent -> h.waitedMs >= floor
        is Handshake.Reset, Handshake.Mirage -> true
        is Handshake.Ok, Handshake.Refused, Handshake.NoRoute, is Handshake.Unknown -> false
    }
    val farClosed = line.abroad.count { closed(it.handshake) }
    val ownClosed = line.abroad.any { s ->
        when (s.role) {
            Role.Own -> closed(s.handshake)
            Role.Public -> false
        }
    }
    val all = (line.russia + line.abroad).map { it.handshake }
    val somebodyAnswered = all.any(::answered)
    val offline = !somebodyAnswered && line.russia.any { silentLink(it.handshake) } && line.abroad.any { silentLink(it.handshake) }
    return when {
        farOk >= QUORUM && !ownClosed -> RoundKind.Open
        ruOk.size >= QUORUM && farClosed >= QUORUM -> RoundKind.WhitelistLike
        offline -> RoundKind.Offline
        else -> RoundKind.Unclear
    }
}

/** DNS нижней сети: не знаем, работает или сломан (имена не резолвятся, а связь по IP есть). */
enum class DnsHealth { Unknown, Works, Broken }

/**
 * DNS сети по раунду. «Не резолвится» бывает только у проб по имени — пробам по IP DNS не нужен, и их рукопожатие
 * доказывает, что связь есть. Сломан — от [QUORUM] сбоев DNS при чьём-то рукопожатии; работает — сбоев DNS нет;
 * иначе не знаем: один сбой — случайность, а без рукопожатий не отличить от «нет сети».
 */
fun dnsOf(line: LineObservation): DnsHealth {
    val all = line.russia + line.abroad
    val failed = all.count { s ->
        when (val h = s.handshake) {
            is Handshake.Unknown -> h.what == DNS_FAILURE
            is Handshake.Ok, is Handshake.Silent, is Handshake.Reset, Handshake.Mirage, Handshake.Refused, Handshake.NoRoute -> false
        }
    }
    val linked = all.any { it.handshake is Handshake.Ok }
    return when {
        failed == 0 -> DnsHealth.Works
        failed >= QUORUM && linked -> DnsHealth.Broken
        else -> DnsHealth.Unknown
    }
}

/** DNS сети по последнему раунду диагноза; раундов нет — не знаем. */
fun dnsOf(d: Diagnosis): DnsHealth = when (d) {
    Diagnosis.Pending -> DnsHealth.Unknown
    is Diagnosis.Suspected -> dnsOf(d.first)
    is Diagnosis.Settled -> dnsOf(d.rounds.last())
    is Diagnosis.Rechecking -> dnsOf(d.previous.rounds.last())
}

/** Что пишет край в [Handshake.Unknown], когда имя эталона не резолвится. */
const val DNS_FAILURE = "dns"

/** Кто-то ответил хоть чем-то: рукопожатие, сброс, подмена, отказ, или TCP есть, а ServerHello нет. */
private fun answered(h: Handshake): Boolean = when (h) {
    is Handshake.Ok, is Handshake.Reset, Handshake.Mirage, Handshake.Refused -> true
    is Handshake.Silent -> when (h.stage) {
        Stage.Tls -> true
        Stage.Connect -> false
    }
    Handshake.NoRoute, is Handshake.Unknown -> false
}

/** Проба показала, что связи нет: SYN без ответа или нет маршрута. */
private fun silentLink(h: Handshake): Boolean = when (h) {
    is Handshake.Silent -> when (h.stage) {
        Stage.Connect -> true
        Stage.Tls -> false
    }
    Handshake.NoRoute -> true
    is Handshake.Ok, is Handshake.Reset, Handshake.Mirage, Handshake.Refused, is Handshake.Unknown -> false
}

/**
 * Сценарий по раундам одного диагноза. Белые списки — только два раунда «похоже» подряд;
 * один — подозрение. Белый список, увиденный линией, — это белый список и на Wi-Fi (раздача, карманный роутер).
 */
fun scenarioOf(os: OsNetwork, rounds: Nel<LineObservation>): Scenario {
    val kinds = rounds.map(::roundOf)
    return when (kinds.last()) {
        RoundKind.Open -> when (os) {
            OsNetwork.Wifi -> Scenario.Wifi
            is OsNetwork.Cellular, OsNetwork.None -> Scenario.MobileOpen
        }
        RoundKind.WhitelistLike ->
            if (kinds.dropLast(1).lastOrNull() == RoundKind.WhitelistLike) Scenario.MobileWhitelist else Scenario.SuspectWhitelist
        RoundKind.Offline -> Scenario.Offline
        RoundKind.Unclear -> if (RoundKind.WhitelistLike in kinds) Scenario.SuspectWhitelist else Scenario.Unclear
    }
}

/** Нужен ли ещё раунд: первый раунд диагноза похож на белые списки. */
fun needsConfirmation(rounds: Nel<LineObservation>): Boolean =
    rounds.size == 1 && roundOf(rounds.head) == RoundKind.WhitelistLike
