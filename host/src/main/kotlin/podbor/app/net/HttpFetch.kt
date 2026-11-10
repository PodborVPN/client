package podbor.app.net

import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import podbor.app.sub.Fetched
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL

/** Потолок тела подписки: больше — не подписка (страница, файл), читать дальше незачем. */
const val SUBSCRIPTION_CAP_BYTES = 2 * 1024 * 1024

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

/** Потолок всего скачивания: по нему сторож рвёт соединение. */
private const val DOWNLOAD_TIMEOUT_MS = 30_000L

/** Не больше [cap] байт как UTF-8; длиннее — null (не подписка). */
fun readCapped(input: InputStream, cap: Int): String? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray().decodeToString()
        if (out.size() + n > cap) return null
        out.write(buf, 0, n)
    }
}

/**
 * Скачивание мимо VPN и через SOCKS сервера. [direct] — соединение обычной сетью платформы; её нет — null.
 * Ссылка секретна: в [warn] — только вид сбоя.
 */
class HttpFetch(
    private val direct: (URL) -> HttpURLConnection?,
    private val warn: (String) -> Unit = ::warnToStderr,
) {
    /** Не 2xx, сеть пропала, срок, больше [SUBSCRIPTION_CAP_BYTES] — null. Заголовки ответа — с именами в нижнем регистре. */
    suspend fun download(url: String, headers: Map<String, String> = emptyMap()): Fetched? {
        val c = direct(URI.create(url).toURL())
        if (c == null) return null
        return fetch({ c.with(headers) }, ::bodyOf)
    }

    /** То же туннелем: SOCKS сервера [socksPort] на этой машине — запрос выходит там, где этот сервер. */
    suspend fun downloadVia(url: String, socksPort: Int, headers: Map<String, String> = emptyMap()): Fetched? =
        fetch({ viaSocks(url, socksPort).with(headers) }, ::bodyOf)

    /** Куда редиректит ссылка: Location ответа 3xx, тело не читается. [socksPort] — туннелем, null — обычной сетью. */
    suspend fun location(url: String, socksPort: Int?): String? {
        val open: () -> HttpURLConnection = when (socksPort) {
            null -> {
                val c = direct(URI.create(url).toURL())
                if (c == null) return null
                { c }
            }
            else -> { { viaSocks(url, socksPort) } }
        }
        return fetch({ open().apply { instanceFollowRedirects = false } }) { c ->
            if (c.responseCode in 300..399) c.getHeaderField("Location") else null
        }
    }

    private fun HttpURLConnection.with(headers: Map<String, String>): HttpURLConnection =
        apply { headers.forEach(::setRequestProperty) }

    private fun viaSocks(url: String, socksPort: Int): HttpURLConnection =
        URI.create(url).toURL().openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))) as HttpURLConnection

    private fun bodyOf(c: HttpURLConnection): Fetched? {
        if (c.responseCode !in 200..299) return null
        val body = c.inputStream.use { readCapped(it, SUBSCRIPTION_CAP_BYTES) }
        return if (body == null) null else Fetched(body, headersOf(c))
    }

    private suspend fun <T : Any> fetch(open: () -> HttpURLConnection, read: (HttpURLConnection) -> T?): T? = Either.catch {
        coroutineScope {
            val c = open()
            // Блокирующее чтение сокета на прерывание потока не реагирует: соединение рвёт сторож — по сроку или
            // при отмене сразу.
            val watchdog = launch { try { delay(DOWNLOAD_TIMEOUT_MS) } finally { c.disconnect() } }
            try {
                runInterruptible(Dispatchers.IO) {
                    c.connectTimeout = CONNECT_TIMEOUT_MS
                    c.readTimeout = READ_TIMEOUT_MS
                    read(c)
                }
            } finally {
                watchdog.cancel()
                c.disconnect()
            }
        }
    }.onLeft { warn("не скачалось: ${it.javaClass.simpleName}") }.getOrNull()

    /** Заголовки ответа: имя в нижнем регистре → первое значение; строка статуса (имя null) — не заголовок. */
    private fun headersOf(c: HttpURLConnection): Map<String, String> =
        c.headerFields.entries.filter { it.key != null }.associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() }
}
