package podbor.app.sub

import arrow.core.Either
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

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
    val supportUrl: String? = null,
    val announce: String? = null,
)

private const val TITLE = "profile-title"
private const val USERINFO = "subscription-userinfo"
private const val SUPPORT = "support-url"
private const val ANNOUNCE = "announce"

/** Сведения провайдера из ответа [f]; не сказал ничего — null. */
fun providerOf(f: Fetched): ProviderInfo? {
    fun value(key: String): String? = (f.headers[key] ?: bodyHeader(f.body, key))?.trim()?.let(::decoded)?.takeIf(String::isNotEmpty)
    val usage = value(USERINFO)?.let(::userinfoOf).orEmpty()
    val used = listOfNotNull(usage["upload"], usage["download"]).takeIf { it.isNotEmpty() }?.sum()
    val info = ProviderInfo(
        title = value(TITLE)?.take(25),
        usedBytes = used,
        totalBytes = usage["total"],
        expireS = usage["expire"],
        supportUrl = value(SUPPORT)?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
        announce = value(ANNOUNCE)?.take(200),
    )
    return info.takeIf { it != ProviderInfo() }
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

/** Что показать о подписке: имя, строки сведений, ссылка поддержки, объявление провайдера. */
data class SubscriptionNote(
    val title: String?,
    val lines: List<String>,
    val supportUrl: String?,
    val announce: String?,
)

private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/** Слова о подписке [s] на момент [nowMs] в поясе [zone]: свежесть, срок, трафик — что известно. */
fun noteOf(s: Subscription, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): SubscriptionNote {
    val p = s.provider
    val fresh = when {
        s.link == null -> "добавлена текстом — сама не обновляется"
        s.fetchedAtMs == null -> null
        else -> "обновлена ${ago(nowMs - s.fetchedAtMs)}"
    }
    val expire = p?.expireS?.takeIf { it > 0 }?.let { at ->
        val day = DAY.format(Instant.ofEpochSecond(at).atZone(zone))
        if (at * 1000 <= nowMs) "оплачена до $day — срок вышел" else "оплачена до $day"
    }
    val traffic = p?.usedBytes?.let { used ->
        val total = p.totalBytes?.takeIf { it > 0 }
        if (total == null) "потрачено ${gb(used)}" else "потрачено ${gb(used)} из ${gb(total)}"
    }
    return SubscriptionNote(p?.title, listOfNotNull(fresh, expire, traffic), p?.supportUrl, p?.announce)
}

private fun ago(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 48 * 60 -> "${min / 60} ч назад"
        else -> "${min / (24 * 60)} дн назад"
    }
}

/** Байты в ГБ: до 10 — с десятой, дальше — целыми. */
private fun gb(bytes: Long): String {
    val v = bytes / 1_073_741_824.0
    return if (v < 10) "%.1f ГБ".format(java.util.Locale.ROOT, v).replace('.', ',') else "${v.toLong()} ГБ"
}
