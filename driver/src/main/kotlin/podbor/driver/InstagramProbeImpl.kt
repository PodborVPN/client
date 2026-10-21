package podbor.driver

import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** Запас сторожа сверх срока: чтение, застрявшее в блокирующем вызове, рвётся не позже срок + запас. */
private const val CLOSE_GRACE_MS = 200L

/**
 * Проба Инсты через SOCKS-inbound кандидата (Android и JVM). С SOCKS-прокси хост разрешается удалённо — так и нужно.
 * Обе части параллельно, срок каждой — PROBE_DEADLINE_MS, как у пробы Телеги; редиректы не следуем: 302 с www и есть ответ.
 */
class InstagramProbeImpl(
    private val deadlineMs: Long = PROBE_DEADLINE_MS,
    private val cdnUrl: String = INSTAGRAM_CDN_URL,
    private val wwwUrl: String = INSTAGRAM_WWW_URL,
) : InstagramProbe {
    override suspend fun probe(socksPort: Int): InstagramAnswer = coroutineScope {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val cdn = async(Dispatchers.IO) { fetch(proxy, cdnUrl, INSTAGRAM_CDN_MIN_BYTES) }
        val www = async(Dispatchers.IO) { fetch(proxy, wwwUrl, 0) }
        val c = cdn.await(); val w = www.await()
        InstagramAnswer(c?.first, c?.second ?: 0, w?.first)
    }

    /**
     * (код, прочитано байт) или null при сбое; читаем не больше readUpTo и обрываем. Отмена не сбой.
     * Срок — абсолютный: цикл чтения кончается по нему (частичные байты — ответ). Блокирующие connect/чтение сокета на
     * прерывание потока не реагируют, поэтому соединение рвёт сторож — при отмене сразу, иначе чуть позже срока.
     */
    private suspend fun fetch(proxy: Proxy, url: String, readUpTo: Int): Pair<Int, Int>? = coroutineScope {
        val conn = URI.create(url).toURL().openConnection(proxy) as HttpURLConnection
        val watchdog = launch { try { delay(deadlineMs + CLOSE_GRACE_MS) } finally { conn.disconnect() } }
        val deadline = System.nanoTime() + deadlineMs * 1_000_000
        Either.catch {
            runInterruptible(Dispatchers.IO) {
                try {
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = deadlineMs.toInt()
                    conn.readTimeout = deadlineMs.toInt()
                    val code = conn.responseCode
                    var read = 0
                    if (readUpTo > 0 && code == 200) {
                        conn.inputStream.use { i ->
                            val buf = ByteArray(8192)
                            while (read < readUpTo && System.nanoTime() < deadline) {
                                val n = i.read(buf)
                                if (n < 0) break
                                read += n
                            }
                        }
                    }
                    code to read
                } finally {
                    conn.disconnect()
                }
            }
        }.getOrElse { null }.also { watchdog.cancel(); currentCoroutineContext().ensureActive() }
    }
}
