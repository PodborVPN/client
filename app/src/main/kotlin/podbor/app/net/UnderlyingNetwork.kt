package podbor.app.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import arrow.core.Either
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import podbor.app.sub.Fetched
import podbor.driver.Resolver
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture

/**
 * Нижележащая сеть — активная не-VPN: через неё резолвятся эталоны линии и серверы подписки,
 * мимо туннеля и его DNS.
 */
class UnderlyingNetwork(private val cm: ConnectivityManager) {

    /** Активная сеть, если она не VPN; иначе первая не-VPN сеть с интернетом. null — сети нет. */
    fun network(): Network? {
        val active = cm.activeNetwork
        if (active != null && isUnderlying(active)) return active
        // allNetworks устарел, но другого синхронного способа увидеть сеть под своим же VPN нет.
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { isUnderlying(it) }
    }

    /**
     * Сеть под VPN могла смениться: появилась, пропала или поменяла возможности не-VPN сеть с интернетом. Сразу при
     * подписке — тоже, чтобы было от чего отсчитывать. Что за сеть — читает подписчик ([transport]): событие — только
     * повод.
     */
    fun changes(): Flow<Unit> = merge(callbackChanges(), flow {
        while (true) {
            delay(2_000)
            emit(Unit)
        }
    }).conflate()

    private fun callbackChanges(): Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(Unit) }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        trySend(Unit)
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate()

    fun transport(): Transport {
        val net = network()
        val caps = if (net == null) null else cm.getNetworkCapabilities(net)
        if (caps == null) return Transport.None
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
        val net = network()
        if (net == null) throw UnknownHostException("нет нижележащей сети")
        return CompletableFuture.supplyAsync({ net.getAllByName(host).first() }, Dispatchers.IO.asExecutor()).await()
    }

    /** Порт Resolver для locate: IP строкой; не разрешилось — null. */
    val resolver: Resolver = Resolver { host -> Either.catch { resolve(host) }.fold({ null }, { it.hostAddress }) }

    private val http = HttpFetch(
        direct = { url ->
            val net = network()
            if (net == null) null else net.openConnection(url) as HttpURLConnection
        },
        warn = { Log.w("Podbor", it) },
    )

    /** Тело по ссылке (подписка) нижележащей сетью: при поднятом VPN туннель для этого не нужен. См. [HttpFetch.download]. */
    suspend fun download(url: String, headers: Map<String, String> = emptyMap()): Fetched? = http.download(url, headers)

    /** То же туннелем: свой трафик Podbor мимо VPN, поэтому прокси указывается явно. См. [HttpFetch.downloadVia]. */
    suspend fun downloadVia(url: String, socksPort: Int, headers: Map<String, String> = emptyMap()): Fetched? =
        http.downloadVia(url, socksPort, headers)

    /** Куда редиректит ссылка: см. [HttpFetch.location]. */
    suspend fun location(url: String, socksPort: Int?): String? = http.location(url, socksPort)

    private fun isUnderlying(n: Network): Boolean {
        val caps = cm.getNetworkCapabilities(n)
        if (caps == null) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
