package podbor.app.sub

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import podbor.screen.InputError
import podbor.screen.SubscriptionInput

private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/**
 * http(s):// одной строкой без пробелов — ссылка; иное — текст; пусто — Empty. Ссылка, которую не разобрать как URI с
 * хостом (запрещённые знаки, кириллический хост), — Unreadable: скачивание её не примет, и «сеть не отвечает» было бы
 * неправдой. Зашифрованная ссылка Happ — HappOnly: её откроет только Happ, расшифровывать чужую защиту не берёмся.
 */
fun inputOf(raw: String): Either<InputError, SubscriptionInput> {
    val s = raw.trim()
    return when {
        s.isEmpty() -> InputError.Empty.left()
        s.startsWith("happ://crypt", ignoreCase = true) -> InputError.HappOnly.left()
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
 * прокси Яндекс Переводчика ([viaYandex]) — своим путём [proxy]: заголовки устройства провайдера Яндексу не уходят.
 * Заголовки ответа прокси — Яндекса, не провайдера: их не берём. Провайдер ответил отказом по лимиту устройств
 * (`x-hwid-limit` при 200, тело пустое или заглушки) — DeviceLimit со словами его `announce`.
 */
suspend fun fetchText(
    input: SubscriptionInput,
    download: suspend (String) -> Fetched?,
    whitelist: suspend () -> Boolean,
    tunnel: (suspend (String) -> Fetched?)? = null,
    proxy: suspend (String) -> Fetched? = download,
): Either<InputError, Fetched> = when (input) {
    is SubscriptionInput.Text -> Fetched(input.text).right()
    is SubscriptionInput.Link -> {
        val got = download(input.url) ?: run {
            val white = whitelist()
            val around = if (!white) null else tunnel?.invoke(input.url)
                ?: proxy(viaYandex(input.url))?.body?.let(::unwrapTranslated)?.let(::Fetched)
            around ?: return InputError.Download(white).left()
        }
        checked(got)
    }
}

/** Скачанное — подписка, если провайдер не отказал по лимиту устройств и тело не пустое. */
private fun checked(got: Fetched): Either<InputError, Fetched> = when {
    got.headers["x-hwid-limit"] == "true" -> providerOf(got).let { InputError.DeviceLimit(it.announce, it.support) }.left()
    got.body.isBlank() -> InputError.Unreadable.left()
    else -> got.right()
}

/** Ссылка через прокси Яндекс Переводчика: так раздают подписки для белых списков; ссылка уходит Яндексу. */
fun viaYandex(url: String): String =
    "https://translate.yandex.ru/translate?url=${URLEncoder.encode(url, "UTF-8")}&lang=de-de"

/**
 * Тело ответа прокси Яндекса. Источник без charset в Content-Type прокси отдаёт байт в байт; с charset (raw.github,
 * панели на FastAPI) — HTML-страницей перевода, где исходный текст лежит в `<pre>` (сверено байт в байт: vless,
 * base64 1,3 МБ, кириллица). Подписка с `<` не начинается — по этому и различаем. Текст `<pre>` — разбором jsoup:
 * теги внутри сняты, сущности раскрыты по стандарту HTML. HTML без `<pre>` («Ошибка перевода») — null.
 */
fun unwrapTranslated(body: String): String? =
    if (!body.trimStart().startsWith("<")) body else Jsoup.parse(body).selectFirst("pre")?.wholeText()

