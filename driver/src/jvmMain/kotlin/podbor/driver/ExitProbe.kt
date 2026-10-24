package podbor.driver

import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import podbor.core.WhiteLists
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Адрес, с которого наш эталон отвечает IP запроса; хост — из единственного описания эталона. */
val EXIT_PROBE_URL = "https://${PODBOR_REF.host}/ip"

/** Запас сторожа сверх срока (как у пробы Инсты). */
private const val EXIT_CLOSE_GRACE_MS = 200L
private const val EXIT_BODY_MAX = 512

/**
 * Эталон — свой VPS с самоподписанным сертификатом: доверие только закреплённому отпечатку ([PinnedTrust]), имя не проверяем.
 * Срок — один абсолютный на всё (connect, рукопожатие, чтение): каждая фаза получает остаток, а не полный срок. Блокирующие
 * вызовы на прерывание не реагируют, поэтому сокет рвёт сторож — при отмене сразу, иначе чуть позже срока.
 * Хост уходит прокси неразрешённым (удалённый DNS); запрос HTTP/1.0 — ответ без chunked, конец по закрытию.
 */
class ExitProbeImpl(
    private val deadlineMs: Long = PROBE_DEADLINE_MS,
    private val url: String = EXIT_PROBE_URL,
    pin: String = (PODBOR_REF.trust as Trust.Pinned).sha256,
) : ExitProbe {
    private val tls: SSLContext by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(pin)), SecureRandom()) } }

    override suspend fun exitIp(socksPort: Int): String? = coroutineScope {
        val target = URI.create(url)
        val secure = target.scheme == "https"
        val port = if (target.port >= 0) target.port else if (secure) 443 else 80
        val raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        val watchdog = launch { try { delay(deadlineMs + EXIT_CLOSE_GRACE_MS) } finally { Either.catch { raw.close() } } }
        val end = System.nanoTime() + deadlineMs * 1_000_000
        fun left(): Int = ((end - System.nanoTime()) / 1_000_000).coerceIn(1, deadlineMs).toInt()
        Either.catch {
            runInterruptible(Dispatchers.IO) {
                raw.use {
                    raw.connect(InetSocketAddress.createUnresolved(target.host, port), left())
                    val s: Socket = if (secure) {
                        (tls.socketFactory.createSocket(raw, target.host, port, true) as SSLSocket).also {
                            it.soTimeout = left()
                            it.startHandshake()
                        }
                    } else raw
                    s.getOutputStream().apply {
                        write("GET ${target.path} HTTP/1.0\r\nHost: ${target.host}\r\nConnection: close\r\n\r\n".toByteArray()); flush()
                    }
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(256)
                    while (out.size() < EXIT_BODY_MAX && System.nanoTime() < end) {
                        s.soTimeout = left()
                        // Сервер может закрыть без close_notify: уже прочитанное — ответ.
                        val n = try { s.getInputStream().read(buf) } catch (e: java.io.IOException) { if (out.size() > 0) -1 else throw e }
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    exitIpOfResponse(out.toString(Charsets.ISO_8859_1))
                }
            }
        }.getOrElse { null }.also { watchdog.cancel(); currentCoroutineContext().ensureActive() }
    }
}

/** Сырой HTTP-ответ → IP: только 200, тело после пустой строки. */
internal fun exitIpOfResponse(raw: String): String? {
    val split = raw.indexOf("\r\n\r\n")
    if (split < 0) return null
    val status = raw.substringBefore("\r\n").split(' ').getOrNull(1)
    return if (status == "200") exitIpOf(raw.substring(split + 4)) else null
}

/** Тело ответа эталона → IP: обрезаем пробелы и перевод строки, не IP — null. */
fun exitIpOf(body: String): String? = body.trim().takeIf { WhiteLists.ipBits(it).isSome() }
