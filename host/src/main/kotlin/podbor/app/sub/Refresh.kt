package podbor.app.sub

import arrow.core.Either
import java.net.URI

/** Обновлять подписку, когда провайдер не сказал, как часто. */
const val DEFAULT_UPDATE_HOURS = 12

/** Границы интервала провайдера: чаще часа — лишний трафик в белых, реже суток — протухший список. */
private val UPDATE_HOURS = 1..24

private const val UPDATE_INTERVAL = "profile-update-interval"

/**
 * Интервал обновления от провайдера, часы: заголовок ответа `profile-update-interval` (панели Marzban, Remnawave)
 * или та же строка шапкой в теле — `# profile-update-interval: 1` (так делают раздачи списков для белых). Заголовок важнее; не число — null.
 */
fun updateHoursOf(f: Fetched): Int? {
    val hours = rawValue(f, UPDATE_INTERVAL).orEmpty().trim().toIntOrNull()
    return if (hours == null) null else hours.coerceIn(UPDATE_HOURS)
}

/** Значение провайдера как прислано: заголовок ответа важнее строки шапки тела. */
internal fun rawValue(f: Fetched, key: String): String? {
    val header = f.headers[key]
    return if (header == null) bodyHeader(f.body, key) else header
}

/** Значение строки шапки `#ключ: значение` в начале тела; шапка — подряд идущие строки с `#`. */
internal fun bodyHeader(body: String, key: String): String? {
    val line = body.lineSequence().map(String::trim).takeWhile { it.startsWith("#") }
        .map { it.removePrefix("#").trim() }
        .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
    return if (line == null) null else line.substringAfter(':')
}

/** Пора обновить: подписка по ссылке и с прошлого скачивания прошёл её интервал (не скачивали ни разу — пора). */
fun refreshDue(s: Subscription, nowMs: Long): Boolean {
    if (s.link == null) return false
    val at = s.fetchedAtMs
    if (at == null) return true
    val hours = s.updateHours
    return nowMs - at >= (if (hours == null) DEFAULT_UPDATE_HOURS else hours) * 3_600_000L
}

/**
 * Подписка после скачивания [f] в [nowMs]: интервал, сведения и запасная ссылка провайдера — из этого ответа, а нет их там
 * (ответ прокси Яндекса без заголовков) — прежние. Провайдер сказал, что подписка переехала, — ссылка новая ([movedLink]).
 */
fun Subscription.fetched(f: Fetched, nowMs: Long): Subscription {
    val old = link
    val moved = if (old == null) null else movedLink(old, f)
    val hours = updateHoursOf(f)
    val fallback = checkedUrl(f, FALLBACK_URL)
    return copy(
        link = if (moved == null) old else moved,
        text = f.body,
        fetchedAtMs = nowMs,
        updateHours = if (hours == null) updateHours else hours,
        provider = providerOf(f).orElse(provider),
        fallbackUrl = if (fallback == null) fallbackUrl else fallback,
    )
}

private const val NEW_URL = "new-url"
private const val NEW_DOMAIN = "new-domain"
private const val FALLBACK_URL = "fallback-url"

/** Домен без пути, порта и мусора: `new.example`. */
private val DOMAIN = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$""")

/** Значение провайдера: заголовок ответа или строка шапки тела; заголовок важнее. */
private fun valueOf(f: Fetched, key: String): String? = rawValue(f, key).orEmpty().trim().takeIf(String::isNotEmpty)

/** Ссылка http(s) с хостом — такую примет скачивание; иначе null. */
private fun httpUrlOf(s: String): String? =
    Either.catch { URI(s) }.fold({ null }, { if ((it.scheme == "https" || it.scheme == "http") && it.host != null) s else null })

/** Значение провайдера [key], если это годная ссылка. */
private fun checkedUrl(f: Fetched, key: String): String? {
    val value = valueOf(f, key)
    return if (value == null) null else httpUrlOf(value)
}

/**
 * Куда переехала подписка [link] по ответу [f] (провайдеру заблокировали домен): `new-url` — ссылка целиком,
 * `new-domain` — только домен, путь, токен и порт прежние. Не сказал или мусор — null.
 */
fun movedLink(link: String, f: Fetched): String? {
    val whole = checkedUrl(f, NEW_URL)
    if (whole != null) return whole
    val domain = valueOf(f, NEW_DOMAIN)
    if (domain == null || !DOMAIN.matches(domain)) return null
    val u = Either.catch { URI(link) }.getOrNull()
    if (u == null || u.host == null) return null
    val userInfo: String? = u.rawUserInfo
    val query: String? = u.rawQuery
    val fragment: String? = u.rawFragment
    return buildString {
        append(u.scheme).append("://")
        if (userInfo != null) append(userInfo).append('@')
        append(domain)
        if (u.port != -1) append(':').append(u.port)
        append(u.rawPath.orEmpty())
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }
}
