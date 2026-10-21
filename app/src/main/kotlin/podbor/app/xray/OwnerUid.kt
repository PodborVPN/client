package podbor.app.xray

import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
import arrow.core.Either
import libXray.ProcessFinder
import java.net.InetSocketAddress

/**
 * Чьё соединение из TUN для правила `process` Xray: libXray спрашивает UID, правило сравнивает его числом.
 *
 * Версии делит libXray (`controller/controller_android.go`, v1.261017.0): ниже API 30 он сам разбирает `/proc/net/tcp|udp`
 * и зовёт нас, только если разбор не удался; с API 30 — всегда нас. Наш способ — [ConnectivityManager.getConnectionOwnerUid],
 * он есть с API 29 и отвечает только держателю VPN про соединения его туннеля.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class OwnerUidFinder(private val cm: ConnectivityManager) : ProcessFinder {

    override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
        val protocol = when (network) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return Process.INVALID_UID.toLong()
        }
        // SecurityException — VPN уже не наш (отозван между соединением и вопросом): чьё — неизвестно.
        return Either.catch {
            cm.getConnectionOwnerUid(protocol, InetSocketAddress(srcIP, srcPort.toInt()), InetSocketAddress(destIP, destPort.toInt()))
        }.getOrNull()?.toLong() ?: Process.INVALID_UID.toLong()
    }
}

/** Ниже API 29 спросить систему нечем: остаётся разбор `/proc/net/tcp|udp` внутри libXray, при его неудаче — «не знаю». */
private object NoOwner : ProcessFinder {
    override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long =
        Process.INVALID_UID.toLong()
}

/** Регистрация на процесс (Go-рантайм один): до первого runXray. SDK передаётся libXray — по нему он выбирает `/proc`. */
fun registerOwnerFinder(cm: ConnectivityManager) {
    val finder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) OwnerUidFinder(cm) else NoOwner
    libXray.LibXray.registerProcessFinder(finder, Build.VERSION.SDK_INT.toLong())
}
