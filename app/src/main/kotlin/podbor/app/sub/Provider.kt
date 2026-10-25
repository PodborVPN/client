package podbor.app.sub

import arrow.core.Either
import arrow.core.toNonEmptyListOrNone
import podbor.core.SubscriptionFacts
import podbor.core.Traffic
import podbor.screen.SubscriptionCard
import podbor.screen.Subscriptions
import podbor.screen.Term
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import podbor.screen.Announce
import podbor.screen.Support
import podbor.screen.SubscriptionNote

/**
 * Что провайдер сказал о подписке в ответе: стандарт панелей (Marzban, Remnawave, Happ) —
 * заголовки ответа или те же строки шапкой в теле. Только сведения для человека, ничем не управляют.
 */
@Serializable
data class ProviderInfo(
    val title: String? = null,
    /** Трафик, байты: потрачено (upload + download) и всего; 0 у total — без лимита. */
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    /** До какого момента оплачено, секунды Unix; 0 — бессрочно. */
    val expireS: Long? = null,
    val support: Support = Support.None,
    val announce: Announce = Announce.None,
) {
    /** Провайдер не сказал ничего — ответ без заголовков (прокси Яндекса) или панель молчит. */
    val silent: Boolean get() = this == ProviderInfo()

    /** Сведения нового ответа; промолчал — прежние [old]. */
    fun orElse(old: ProviderInfo): ProviderInfo = if (silent) old else this
}

private const val TITLE = "profile-title"
private const val USERINFO = "subscription-userinfo"
private const val SUPPORT = "support-url"
private const val ANNOUNCE = "announce"

/** Сведения провайдера из ответа [f]; не сказал ничего — пустые ([ProviderInfo.silent]). */
fun providerOf(f: Fetched): ProviderInfo {
    fun value(key: String): String? = (f.headers[key] ?: bodyHeader(f.body, key))?.trim()?.let(::decoded)?.takeIf(String::isNotEmpty)
    val usage = value(USERINFO)?.let(::userinfoOf).orEmpty()
    val used = listOfNotNull(usage["upload"], usage["download"]).takeIf { it.isNotEmpty() }?.sum()
    return ProviderInfo(
        title = value(TITLE)?.take(25),
        usedBytes = used,
        totalBytes = usage["total"],
        expireS = usage["expire"],
        support = supportOf(value(SUPPORT)),
        announce = announceOf(value(ANNOUNCE)),
    )
}

/** Поддержка — только ссылкой http/https: другие схемы (`javascript:`, `intent:`) экран не открывает. */
private fun supportOf(raw: String?): Support = when {
    raw == null -> Support.None
    raw.startsWith("https://") || raw.startsWith("http://") -> Support.Link(raw)
    else -> Support.None
}

/** Объявление — до 200 знаков: длиннее на экране всё равно две строки. */
private fun announceOf(raw: String?): Announce = when (raw) {
    null -> Announce.None
    else -> Announce.Text(raw.take(200))
}

/** `base64:…` — значение в base64 (так шлют имена с эмодзи и кириллицей в заголовке); иначе как есть. */
private fun decoded(v: String): String? =
    if (!v.startsWith("base64:")) v
    else Either.catch { String(Base64.getDecoder().decode(v.removePrefix("base64:").trim()), Charsets.UTF_8) }.getOrNull()

/** `upload=1; download=2; total=3; expire=4` → числа по именам; не число — пропуск. */
private fun userinfoOf(v: String): Map<String, Long> =
    v.split(';').mapNotNull { part ->
        val (k, n) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        n.trim().toLongOrNull()?.let { k.trim().lowercase() to it }
    }.toMap()

private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Слова о подписке [s] на момент [nowMs] в поясе [zone]: свежесть, срок, трафик — что известно. */
fun noteOf(s: Subscription, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SubscriptionNote {
    val p = s.provider
    val fresh = when {
        s.link == null -> "добавлена текстом — сама не обновляется"
        s.fetchedAtMs == null -> null
        else -> "обновлена ${ago(nowMs - s.fetchedAtMs)}"
    }
    val expire = p.expireS?.takeIf { it > 0 }?.let { at ->
        val day = DAY.format(Instant.ofEpochSecond(at).atZone(zone))
        if (at * 1000 <= nowMs) "оплачена до $day — срок вышел" else "оплачена до $day"
    }
    val traffic = p.usedBytes?.let { used ->
        val total = p.totalBytes?.takeIf { it > 0 }
        if (total == null) "потрачено ${gb(used)}" else "потрачено ${gb(used)} из ${gb(total)}"
    }
    return SubscriptionNote(p.title, listOfNotNull(fresh, expire, traffic), p.support, p.announce)
}

/**
 * Срок подписки по словам провайдера на момент [nowMs]: трафик кончился (лимит есть — `total > 0`) — важнее даты; срока
 * нет или 0 (бессрочно) — не знаем; момент срока наступил — кончилась.
 */
fun termOf(p: ProviderInfo, nowMs: Long): Term {
    val used = p.usedBytes
    val total = p.totalBytes
    val expire = p.expireS
    return when {
        used != null && total != null && total > 0 && used >= total -> Term.OutOfTraffic
        expire == null || expire <= 0 -> Term.Unknown
        expire * 1000 <= nowMs -> Term.Expired(expire)
        else -> Term.Until(expire)
    }
}

/** Карточка подписки для экрана — без ссылки и текста. Имени от провайдера нет — по виду подписки. */
fun cardOf(
    s: Subscription,
    nowMs: Long,
    facts: SubscriptionFacts = SubscriptionFacts.Empty,
    viaPodbor: Traffic = Traffic.Zero,
): SubscriptionCard = SubscriptionCard(
    id = s.id,
    title = s.provider.title ?: if (s.link != null) "подписка без имени" else "конфиги текстом",
    servers = s.servers.size,
    analyzed = s.analyzed,
    term = termOf(s.provider, nowMs),
    note = noteOf(s, nowMs),
    facts = facts,
    viaPodbor = viaPodbor,
)

/**
 * Подписки глазами экрана: пусто — нет; текущей нет в списке — не выбрана; иначе текущая первой и прочие по порядку.
 * [factsOf] и [viaOf] — как подписка ведёт себя здесь и сколько прошло через VPN на ней.
 */
fun subscriptionsOf(
    shelf: Shelf,
    nowMs: Long,
    factsOf: (Subscription) -> SubscriptionFacts = { SubscriptionFacts.Empty },
    viaOf: (Subscription) -> Traffic = { Traffic.Zero },
): Subscriptions {
    val cards = shelf.items.map { cardOf(it, nowMs, factsOf(it), viaOf(it)) }
    return cards.toNonEmptyListOrNone().fold({ Subscriptions.None }) { all ->
        when (val current = all.firstOrNull { it.id == shelf.currentId }) {
            null -> Subscriptions.Unchosen(all)
            else -> Subscriptions.Chosen(current, cards - current)
        }
    }
}

/** Давность словами: «только что», «N мин назад», «N ч назад», «N дн назад». */
internal fun ago(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 48 * 60 -> "${min / 60} ч назад"
        else -> "${min / (24 * 60)} дн назад"
    }
}

/** Байты в ГБ: до 10 — с десятой, дальше — целыми. */
internal fun gb(bytes: Long): String {
    val v = bytes / 1_073_741_824.0
    return if (v < 10) "%.1f ГБ".format(java.util.Locale.ROOT, v).replace('.', ',') else "${v.toLong()} ГБ"
}
