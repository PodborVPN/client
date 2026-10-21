package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * SOCKS5 с паролем (RFC 1928/1929) — как вход Xray с `auth: password`: без пароля и с чужим не пускает. После CONNECT
 * отвечает успехом и пишет в сокет [reply]. В [seen] — с какими логинами приходили.
 */
private class FakeSocks(private val auth: SocksAuth, private val reply: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val seen: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { c.use(::serve) }
            }
        }
    }

    private fun serve(c: Socket) {
        val i = DataInputStream(c.getInputStream()); val o = c.getOutputStream()
        i.readUnsignedByte(); val n = i.readUnsignedByte(); val methods = ByteArray(n).also(i::readFully)
        if (2.toByte() !in methods) { seen += null; o.write(byteArrayOf(5, 0xFF.toByte())); return }
        o.write(byteArrayOf(5, 2))
        i.readUnsignedByte()
        val user = String(ByteArray(i.readUnsignedByte()).also(i::readFully))
        val pass = String(ByteArray(i.readUnsignedByte()).also(i::readFully))
        seen += user
        if (user != auth.user || pass != auth.pass) { o.write(byteArrayOf(1, 1)); return }
        o.write(byteArrayOf(1, 0))
        i.readUnsignedByte(); i.readUnsignedByte(); i.readUnsignedByte()
        when (i.readUnsignedByte()) {
            1 -> i.readFully(ByteArray(4))
            3 -> i.readFully(ByteArray(i.readUnsignedByte()))
            4 -> i.readFully(ByteArray(16))
        }
        i.readUnsignedShort()
        o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        o.write(reply); o.flush()
    }

    override fun close() = server.close()
}

class SocksAuthTest : FunSpec({
    test("случайный пароль процесса: разный от раза к разу, не пустой") {
        val a = SocksAuth.random(); val b = SocksAuth.random()
        a shouldNotBe b
        a.user.length shouldBe 32
        a.pass.length shouldBe 32
    }

    test("рукопожатие Телеги с паролем проходит; без пароля и с чужим — Silent, а не ошибка") {
        val auth = SocksAuth("u1", "p1")
        // Ответ «DC»: кадр в 1 байт — Garbage: значит, рукопожатие прошло и до DC дошли (Silent — не дошли).
        FakeSocks(auth, byteArrayOf(1, 0, 0, 0, 7)).use { fake ->
            TelegramProbeImpl(deadlineMs = 1_000, auth = auth).askDc(fake.port, "149.154.167.51", 443) shouldBe DcAnswer.Garbage
            TelegramProbeImpl(deadlineMs = 1_000, auth = null).askDc(fake.port, "149.154.167.51", 443) shouldBe DcAnswer.Silent
            TelegramProbeImpl(deadlineMs = 1_000, auth = SocksAuth("u1", "чужой")).askDc(fake.port, "149.154.167.51", 443) shouldBe DcAnswer.Silent
            fake.seen shouldBe listOf("u1", null, "u1")
        }
    }

    test("Proxy платформы (проба Инсты и выхода, скачивание туннелем): пароль — из Authenticator процесса") {
        val auth = SocksAuth("u2", "p2")
        installSocksAuth(auth)
        FakeSocks(auth, "ok".toByteArray()).use { fake ->
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", fake.port))).use { s ->
                s.connect(InetSocketAddress.createUnresolved("example.org", 80), 2_000)
                s.getInputStream().readNBytes(2).decodeToString() shouldBe "ok"
            }
            fake.seen shouldBe listOf("u2")
        }
    }
})
