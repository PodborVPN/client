package podbor.app.sub

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
 * Подписка после скачивания [f] в [nowMs]: интервал и сведения провайдера — из этого ответа, а нет их там (ответ прокси
 * Яндекса без заголовков) — прежние.
 */
fun Subscription.fetched(f: Fetched, nowMs: Long): Subscription =
    copy(text = f.body, fetchedAtMs = nowMs, updateHours = updateHoursOf(f) ?: updateHours, provider = providerOf(f) ?: provider)
