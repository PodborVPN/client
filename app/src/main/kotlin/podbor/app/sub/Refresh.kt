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
fun updateHoursOf(f: Fetched): Int? =
    (f.headers[UPDATE_INTERVAL] ?: bodyHeader(f.body, UPDATE_INTERVAL))?.trim()?.toIntOrNull()?.coerceIn(UPDATE_HOURS)

/** Значение строки шапки `#ключ: значение` в начале тела; шапка — подряд идущие строки с `#`. */
internal fun bodyHeader(body: String, key: String): String? =
    body.lineSequence().map(String::trim).takeWhile { it.startsWith("#") }
        .map { it.removePrefix("#").trim() }
        .firstOrNull { it.startsWith("$key:", ignoreCase = true) }
        ?.substringAfter(':')

/** Пора обновить: подписка по ссылке и с прошлого скачивания прошёл её интервал (не скачивали ни разу — пора). */
fun refreshDue(s: Subscription, nowMs: Long): Boolean {
    if (s.link == null) return false
    val at = s.fetchedAtMs ?: return true
    return nowMs - at >= (s.updateHours ?: DEFAULT_UPDATE_HOURS) * 3_600_000L
}

/**
 * Подписка после скачивания [f] в [nowMs]: интервал, сведения и запасная ссылка провайдера — из этого ответа, а нет их там
 * (ответ прокси Яндекса без заголовков) — прежние. Провайдер сказал, что подписка переехала, — ссылка новая ([movedLink]).
 */
fun Subscription.fetched(f: Fetched, nowMs: Long): Subscription =
    copy(
        link = link?.let { movedLink(it, f) ?: it },
        text = f.body,
        fetchedAtMs = nowMs,
        updateHours = updateHoursOf(f) ?: updateHours,
        provider = providerOf(f).orElse(provider),
        fallbackUrl = valueOf(f, FALLBACK_URL)?.let(::httpUrlOf) ?: fallbackUrl,
    )

private const val NEW_URL = "new-url"
private const val NEW_DOMAIN = "new-domain"
private const val FALLBACK_URL = "fallback-url"

/** Домен без пути, порта и мусора: `new.example`. */
private val DOMAIN = Regex("""^[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$""")

/** Значение провайдера: заголовок ответа или строка шапки тела; заголовок важнее. */
private fun valueOf(f: Fetched, key: String): String? = (f.headers[key] ?: bodyHeader(f.body, key))?.trim()?.takeIf(String::isNotEmpty)

/** Ссылка http(s) с хостом — такую примет скачивание; иначе null. */
private fun httpUrlOf(s: String): String? =
    Either.catch { URI(s) }.getOrNull()?.takeIf { (it.scheme == "https" || it.scheme == "http") && it.host != null }?.let { s }

/**
 * Куда переехала подписка [link] по ответу [f] (провайдеру заблокировали домен): `new-url` — ссылка целиком,
 * `new-domain` — только домен, путь, токен и порт прежние. Не сказал или мусор — null.
 */
fun movedLink(link: String, f: Fetched): String? {
    valueOf(f, NEW_URL)?.let(::httpUrlOf)?.let { return it }
    val domain = valueOf(f, NEW_DOMAIN)?.takeIf(DOMAIN::matches) ?: return null
    val u = Either.catch { URI(link) }.getOrNull()?.takeIf { it.host != null } ?: return null
    return buildString {
        append(u.scheme).append("://")
        u.rawUserInfo?.let { append(it).append('@') }
        append(domain)
        if (u.port != -1) append(':').append(u.port)
        append(u.rawPath.orEmpty())
        u.rawQuery?.let { append('?').append(it) }
        u.rawFragment?.let { append('#').append(it) }
    }
}
