package podbor.app.sub

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.net.URI
import java.net.URLEncoder

/** Что человек вставил: ссылку на подписку или сами конфиги текстом. Содержимое секретно — в логи не идёт. */
sealed interface SubscriptionInput {
    data class Link(val url: String) : SubscriptionInput
    data class Text(val text: String) : SubscriptionInput
}

sealed interface InputError {
    data object Empty : InputError
    data object Unreadable : InputError
    /** [whitelist] — скачать не вышло, а сеть похожа на «белый» режим. */
    data class Download(val whitelist: Boolean) : InputError
    data object NoServers : InputError
    /** Серверы есть, а Xray анализа не поднялся (конфиг или движок). */
    data object ScanFailed : InputError
}

private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/**
 * http(s):// одной строкой без пробелов — ссылка; иное — текст; пусто — Empty. Ссылка, которую не разобрать как URI с
 * хостом (запрещённые знаки, кириллический хост), — Unreadable: скачивание её не примет, и «сеть не отвечает» было бы
 * неправдой.
 */
fun inputOf(raw: String): Either<InputError, SubscriptionInput> {
    val s = raw.trim()
    return when {
        s.isEmpty() -> InputError.Empty.left()
        LINK.matches(s) -> if (hostOf(s) != null) SubscriptionInput.Link(s).right() else InputError.Unreadable.left()
        else -> SubscriptionInput.Text(s).right()
    }
}

/**
 * Хост ссылки по java.net.URI (тот же разбор, что у скачивания); не разобралась или хоста нет — null. Хост с `_`
 * (`my_sub.example.com`) URI считает не сервером, а «реестровым» authority — `host` у него null; такой берём из
 * authority, если он ASCII-имя (кириллица по-прежнему null: скачивание её не примет).
 */
private fun hostOf(link: String): String? = Either.catch {
    val uri = URI(link)
    uri.host ?: uri.rawAuthority?.substringAfterLast('@')?.substringBeforeLast(':')?.takeIf(ASCII_HOST::matches)
}.getOrNull()

private val ASCII_HOST = Regex("""[A-Za-z0-9._-]+""")

/** Ответ сервера подписки: тело и заголовки (имена — в нижнем регистре); у вставленного текста и прокси — без них. */
data class Fetched(val body: String, val headers: Map<String, String> = emptyMap())

/**
 * Текст подписки: ссылку качаем через [download] (null — не вышло), текст отдаём как есть. Не скачалась, а сеть похожа
 * на белые списки, — дальше по очереди: через туннель найденного выхода ([tunnel]; выхода нет — null) и через
 * прокси Яндекс Переводчика ([viaYandex]): его подсети в белых. Заголовки прокси — Яндекса, не провайдера: их не
 * берём.
 */
suspend fun fetchText(
    input: SubscriptionInput,
    download: suspend (String) -> Fetched?,
    whitelist: suspend () -> Boolean,
    tunnel: (suspend (String) -> Fetched?)? = null,
): Either<InputError, Fetched> = when (input) {
    is SubscriptionInput.Text -> Fetched(input.text).right()
    is SubscriptionInput.Link -> {
        val got = download(input.url) ?: run {
            val white = whitelist()
            val around = if (!white) null else tunnel?.invoke(input.url)
                ?: download(viaYandex(input.url))?.body?.let(::unwrapTranslated)?.let(::Fetched)
            around ?: return InputError.Download(white).left()
        }
        if (got.body.isBlank()) InputError.Unreadable.left() else got.right()
    }
}

/** Ссылка через прокси Яндекс Переводчика: так раздают подписки для белых списков; ссылка уходит Яндексу. */
fun viaYandex(url: String): String =
    "https://translate.yandex.ru/translate?url=${URLEncoder.encode(url, Charsets.UTF_8)}&lang=de-de"

/**
 * Тело ответа прокси Яндекса. Источник без charset в Content-Type прокси отдаёт байт в байт; с charset (raw.github,
 * панели на FastAPI) — HTML-страницей перевода, где исходный текст лежит в `<pre>` (сверено байт в байт: vless,
 * base64 1,3 МБ, кириллица). Подписка с `<` не начинается — по этому и различаем. HTML без `<pre>` («Ошибка перевода»)
 * — null.
 */
fun unwrapTranslated(body: String): String? {
    if (!body.trimStart().startsWith("<")) return body
    val pre = PRE.find(body)?.groupValues?.get(1) ?: return null
    return unescape(pre.replace(TAG, ""))
}

private val PRE = Regex("""<pre[^>]*>(.*?)</pre>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("""<[^>]+>""")
private val ENTITY = Regex("""&(#[0-9]+|#[xX][0-9A-Fa-f]+|amp|lt|gt|quot|apos);""")

private fun unescape(s: String): String = s.replace(ENTITY) { m ->
    when (val e = m.groupValues[1]) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> (if (e[1] == 'x' || e[1] == 'X') e.substring(2).toInt(16) else e.substring(1).toInt())
            .let { Character.toChars(it).concatToString() }
    }
}

fun wordsOf(e: InputError): String = when (e) {
    is InputError.Download ->
        if (e.whitelist) "не могу скачать подписку: похоже, белые списки. Попроси прислать её текстом или QR в VK или Max — или добавь через Wi-Fi"
        else "не могу скачать подписку: сеть не отвечает"
    InputError.NoServers -> "не нашёл поддерживаемых серверов"
    InputError.ScanFailed -> "не смог проверить подписку — попробуй ещё раз"
    InputError.Empty, InputError.Unreadable -> "в буфере нет подписки"
}
