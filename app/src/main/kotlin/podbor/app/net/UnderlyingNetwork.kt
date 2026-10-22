package podbor.app.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.future.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import podbor.app.sub.Fetched
import podbor.driver.Resolver
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture

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
 * Нижележащая сеть — активная не-VPN: через неё резолвятся эталоны линии и серверы подписки,
 * мимо туннеля и его DNS.
 */
class UnderlyingNetwork(private val cm: ConnectivityManager) {

    /** Активная сеть, если она не VPN; иначе первая не-VPN сеть с интернетом. null — сети нет. */
    fun network(): Network? {
        val active = cm.activeNetwork?.takeIf { isUnderlying(it) }
        // allNetworks устарел, но другого синхронного способа увидеть сеть под своим же VPN нет.
        @Suppress("DEPRECATION")
        return active ?: cm.allNetworks.firstOrNull { isUnderlying(it) }
    }

    fun transport(): Transport {
        val caps = network()?.let(cm::getNetworkCapabilities) ?: return Transport.None
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            // Ethernet для ядра — та же домашняя сеть, что Wi-Fi: белых списков оператора там нет.
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Wifi
            else -> Transport.None
        }
    }

    /**
     * Резолв имени DNS нижележащей сети. Блокирующий getAllByName — на пуле IO, ожидание отменяемо
     * (как у TlsHandshaker по умолчанию): зависший резолвер потолок пробы не держит.
     * Нет сети — UnknownHostException, который линия читает как Unknown("dns").
     */
    suspend fun resolve(host: String): InetAddress {
        val net = network() ?: throw UnknownHostException("нет нижележащей сети")
        return CompletableFuture.supplyAsync({ net.getAllByName(host).first() }, Dispatchers.IO.asExecutor()).await()
    }

    /** Порт Resolver для locate: IP строкой; не разрешилось — null. */
    val resolver: Resolver = Resolver { host -> Either.catch { resolve(host) }.getOrNull()?.hostAddress }

    /**
     * Тело по ссылке (подписка, последний релиз на GitHub) через нижележащую сеть (мимо VPN — его на время анализа нет,
     * но и при поднятом туннель не нужен). Не 2xx, сеть пропала, срок, больше [SUBSCRIPTION_CAP_BYTES] — null. Ссылка секретна: в лог — только вид сбоя.
     * Заголовки ответа — с именами в нижнем регистре (провайдер так передаёт интервал обновления).
     */
    suspend fun download(url: String): Fetched? {
        val net = network() ?: return null
        return fetch { net.openConnection(URI.create(url).toURL()) as HttpURLConnection }
    }

    /**
     * То же через туннель: SOCKS сервера [socksPort] на устройстве — запрос выходит там, где этот сервер, а
     * не в белые списки оператора. Свой трафик Podbor мимо VPN, поэтому прокси указывается явно.
     */
    suspend fun downloadVia(url: String, socksPort: Int): Fetched? = fetch {
        URI.create(url).toURL().openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))) as HttpURLConnection
    }

    private suspend fun fetch(open: () -> HttpURLConnection): Fetched? = Either.catch {
        coroutineScope {
            val c = open()
            // Блокирующее чтение сокета на прерывание потока не реагирует: соединение рвёт сторож — по сроку или
            // при отмене сразу (как в InstagramProbeImpl).
            val watchdog = launch { try { delay(DOWNLOAD_TIMEOUT_MS) } finally { c.disconnect() } }
            try {
                runInterruptible(Dispatchers.IO) {
                    c.connectTimeout = CONNECT_TIMEOUT_MS
                    c.readTimeout = READ_TIMEOUT_MS
                    if (c.responseCode !in 200..299) null
                    else c.inputStream.use { readCapped(it, SUBSCRIPTION_CAP_BYTES) }?.let { Fetched(it, headersOf(c)) }
                }
            } finally {
                watchdog.cancel()
                c.disconnect()
            }
        }
    }.onLeft { Log.w("Podbor", "не скачалось: ${it.javaClass.simpleName}") }.getOrNull()

    /** Заголовки ответа: имя в нижнем регистре → первое значение; строка статуса (имя null) — не заголовок. */
    private fun headersOf(c: HttpURLConnection): Map<String, String> =
        c.headerFields.entries.mapNotNull { (k, v) -> k?.let { it.lowercase() to v.firstOrNull().orEmpty() } }.toMap()

    private fun isUnderlying(n: Network): Boolean {
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
