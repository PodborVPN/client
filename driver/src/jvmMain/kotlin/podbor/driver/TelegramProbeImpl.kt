package podbor.driver

import arrow.core.Either
import arrow.core.getOrElse
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import podbor.core.Endpoint
import podbor.core.Reach
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Проба через SOCKS-inbound кандидата: соединение до DC открывает Xray, мы шлём только блоб. Рукопожатие SOCKS5 — своё
 * (блоб идёт сразу за ним); [auth] — пароль входа, null — вход без пароля.
 */
class TelegramProbeImpl(
    private val deadlineMs: Long = PROBE_DEADLINE_MS,
    private val dcs: List<Pair<String, Int>> = PROBE_DCS,
    private val auth: SocksAuth? = null,
) : TelegramProbe {
    override suspend fun probe(socksPort: Int): Int = coroutineScope {
        dcs.map { (ip, port) ->
            async(Dispatchers.IO) { withTimeoutOrNull(deadlineMs) { askDc(socksPort, ip, port) } ?: DcAnswer.Silent }
        }.awaitAll().count { it == DcAnswer.ResPq }
    }

    /**
     * Ответ DC; сбой сокета — Silent, а отмена не ответ: Either.catch пробрасывает её, а ensureActive ловит случай,
     * когда блокирующее чтение сокета неотменяемо и кончилось своим таймаутом уже после отмены.
     */
    internal suspend fun askDc(socksPort: Int, ip: String, port: Int): DcAnswer = resourceScope {
        val socket = install({ Socket() }, { s, _ -> s.close() })
        Either.catch {
            runInterruptible(Dispatchers.IO) {
                socket.connect(InetSocketAddress("127.0.0.1", socksPort), deadlineMs.toInt())
                socket.soTimeout = deadlineMs.toInt()
                val o = socket.getOutputStream(); val i = socket.getInputStream()
                greet(o, i)
                val addr = ip.split('.').map { it.toInt().toByte() }.toByteArray()
                o.write(byteArrayOf(5, 1, 0, 1) + addr + byteArrayOf((port shr 8).toByte(), port.toByte()))
                i.exactly(10)
                o.write(TG_BLOB); o.flush()
                val len = le32(i.exactly(4) ?: return@runInterruptible DcAnswer.Silent, 0)
                judgeResPq(if (len in 1..4096) i.exactly(len) else null)
            }
        }.getOrElse { DcAnswer.Silent }.also { currentCoroutineContext().ensureActive() }
    }

    /**
     * Приветствие SOCKS5: метод «без пароля» или «логин/пароль» (RFC 1929) по [auth]. Вход не принял — исключение:
     * askDc читает его как Silent (сокет не дошёл до DC).
     */
    private fun greet(o: java.io.OutputStream, i: InputStream) {
        val a = auth
        if (a == null) {
            o.write(byteArrayOf(5, 1, 0)); i.exactly(2)
            return
        }
        o.write(byteArrayOf(5, 1, 2))
        val chosen = i.exactly(2) ?: error("SOCKS: нет ответа")
        check(chosen[1] == 2.toByte()) { "SOCKS: пароль не принят как метод" }
        val user = a.user.toByteArray(); val pass = a.pass.toByteArray()
        o.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        val verdict = i.exactly(2) ?: error("SOCKS: нет ответа на пароль")
        check(verdict[1] == 0.toByte()) { "SOCKS: пароль отвергнут" }
    }
}

/** Ровно n байт или null, если поток кончился раньше. */
private fun InputStream.exactly(n: Int): ByteArray? = readNBytes(n).takeIf { it.size == n }

/** TCP-connect к серверу кандидата мимо туннеля (на Android приложение исключено из своего VPN — addDisallowedApplication). */
class SocketTcpReach(private val socketFactory: () -> Socket = ::Socket) : TcpReach {
    override suspend fun reach(endpoint: Endpoint, deadlineMs: Long): Reach = resourceScope {
        val socket = install({ socketFactory() }, { s, _ -> s.close() })
        Either.catch { runInterruptible(Dispatchers.IO) { socket.connect(InetSocketAddress(endpoint.host, endpoint.port), deadlineMs.toInt()) } }
            .fold({ Reach.Down }, { Reach.Up })
            .also { currentCoroutineContext().ensureActive() }
    }
}
