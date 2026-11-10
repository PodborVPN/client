package podbor.app.net

import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
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
import kotlin.time.Duration.Companion.milliseconds

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
    suspend fun download(url: String, headers: Map<String, String> = emptyMap()): Fetched? =
        fetch({ directTo(url) }) { c -> bodyOf(c.with(headers)) }

    /** То же туннелем: SOCKS сервера [socksPort] на этой машине — запрос выходит там, где этот сервер. */
    suspend fun downloadVia(url: String, socksPort: Int, headers: Map<String, String> = emptyMap()): Fetched? =
        fetch({ viaSocks(url, socksPort) }) { c -> bodyOf(c.with(headers)) }

    /** Куда редиректит ссылка: Location ответа 3xx, тело не читается. [socksPort] — туннелем, null — обычной сетью. */
    suspend fun location(url: String, socksPort: Int?): String? {
        val open: () -> HttpURLConnection? = when (socksPort) {
            null -> {
                { directTo(url) }
            }

            else -> {
                { viaSocks(url, socksPort) }
            }
        }
        return fetch(open) { c ->
            c.instanceFollowRedirects = false
            if (c.responseCode in 300..399) c.getHeaderField("Location") else null
        }
    }

    private fun directTo(url: String): HttpURLConnection? = direct(URI.create(url).toURL())

    private fun HttpURLConnection.with(headers: Map<String, String>): HttpURLConnection =
        apply { headers.forEach(::setRequestProperty) }

    private fun viaSocks(url: String, socksPort: Int): HttpURLConnection =
        URI.create(url).toURL()
            .openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))) as HttpURLConnection

    private fun bodyOf(c: HttpURLConnection): Fetched? {
        if (c.responseCode !in 200..299) return null
        val body = c.inputStream.use { readCapped(it, SUBSCRIPTION_CAP_BYTES) }
        return if (body == null) null else Fetched(body, headersOf(c))
    }

    /** Соединение открывается внутри [Either.catch]: сбой открытия — null, как и сбой чтения, а не исключение наружу. */
    private suspend fun <T : Any> fetch(open: () -> HttpURLConnection?, read: (HttpURLConnection) -> T?): T? =
        Either.catch {
            val c = open()
            if (c == null) null else coroutineScope { guarded(c, read) }
        }.onLeft { warn("не скачалось: ${it.javaClass.simpleName}") }.getOrNull()

    private suspend fun <T : Any> CoroutineScope.guarded(c: HttpURLConnection, read: (HttpURLConnection) -> T?): T? {
        // Блокирующее чтение сокета на прерывание потока не реагирует: соединение рвёт сторож — по сроку или
        // при отмене сразу.
        val watchdog = launch {
            try {
                delay(DOWNLOAD_TIMEOUT_MS.milliseconds)
            } finally {
                c.disconnect()
            }
        }
        return try {
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

    /** Заголовки ответа: имя в нижнем регистре → первое значение; строка статуса (имя null) — не заголовок. */
    private fun headersOf(c: HttpURLConnection): Map<String, String> =
        c.headerFields.entries.filter { it.key != null }
            .associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() }
}
