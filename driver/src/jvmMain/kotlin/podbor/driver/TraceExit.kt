package podbor.driver

import arrow.core.Either
import arrow.core.getOrElse
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import podbor.core.Exit
import podbor.core.knownCountry

/** Независимое измерение через тот же SOCKS; HTTPS проверяет системное доверие и имя узла. */
class TraceExit(
    private val url: String = "https://www.cloudflare.com/cdn-cgi/trace",
    private val deadlineMs: Int = PROBE_DEADLINE_MS.toInt(),
) {
    suspend fun measure(port: Int): Exit = coroutineScope {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val connection = URI.create(url).toURL().openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = deadlineMs
        connection.readTimeout = deadlineMs
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        val watchdog = launch { try { delay(deadlineMs.toLong()) } finally { connection.disconnect() } }
        try {
            Either.catch {
                runInterruptible(Dispatchers.IO) {
                    if (connection.responseCode != 200) Exit.Unknown
                    else connection.inputStream.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(512)
                        while (out.size() <= 4096) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                        if (out.size() > 4096) Exit.Unknown else exitOfTrace(out.toString("UTF-8"))
                    }
                }
            }.getOrElse { Exit.Unknown }.also { currentCoroutineContext().ensureActive() }
        } finally {
            watchdog.cancel()
            connection.disconnect()
        }
    }
}

internal fun exitOfTrace(body: String): Exit {
    val fields = body.lineSequence().map { it.trim().split('=', limit = 2) }.filter { it.size == 2 }.associate { it[0] to it[1] }
    val ip = fields["ip"]?.let(::exitIpOf) ?: return Exit.Unknown
    val country = fields["loc"] ?: return Exit.Unknown
    return if (knownCountry(country) && country != "XX" && country != "ZZ") Exit.At(ip, country) else Exit.Unknown
}
