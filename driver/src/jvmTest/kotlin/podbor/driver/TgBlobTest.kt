package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import podbor.core.Endpoint
import podbor.core.Reach
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Ответ DC2 на блоб, записан с живого DC2 (100 байт без 4-байтной длины). */
private const val DC2_RESPQ =
    "00000000000000000160e52df61ad56a50000000632416050123456789abcdef0123456789abcdef10fbda1a7080abf78479e5e6d274fd5708194583c76139890700000015c4b51c0300000085fd64de851d9dd0a5b7f709355fc30b216be86c022bb4c3"

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Фейковый SOCKS5: на CONNECT к ip из answers отвечает заданным кадром, null — молчит; [connects] — принятые соединения. */
private fun fakeSocks(answers: Map<String, ByteArray?>, connects: AtomicInteger = AtomicInteger()): ServerSocket {
    val server = ServerSocket(0)
    thread(isDaemon = true) {
        while (!server.isClosed) {
            val c = runCatching { server.accept() }.getOrNull() ?: break
            connects.incrementAndGet()
            thread(isDaemon = true) { serve(c, answers) }
        }
    }
    return server
}

private fun serve(c: Socket, answers: Map<String, ByteArray?>) = c.use {
    val i = it.getInputStream(); val o = it.getOutputStream()
    i.readNBytes(3); o.write(byteArrayOf(5, 0))
    val req = i.readNBytes(10)
    val ip = req.sliceArray(4..7).joinToString(".") { b -> (b.toInt() and 0xff).toString() }
    o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
    i.readNBytes(TG_BLOB.size)
    answers[ip]?.let { frame ->
        o.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(frame.size).array())
        o.write(frame); o.flush()
    } ?: Thread.sleep(10_000)
}

class TgBlobTest : FunSpec({

    test("judgeResPq: записанный ответ DC2 → ResPq; 4 байта → Garbage; null → Silent") {
        judgeResPq(hex(DC2_RESPQ)) shouldBe DcAnswer.ResPq
        judgeResPq(hex("6cfeffff")) shouldBe DcAnswer.Garbage
        judgeResPq(null) shouldBe DcAnswer.Silent
        val wrongNonce = hex(DC2_RESPQ).also { it[30] = 0 }
        judgeResPq(wrongNonce) shouldBe DcAnswer.Garbage
    }

    test("проба через SOCKS: 3 соединения к кандидату (не больше бюджета), считает ответившие DC и укладывается в срок") {
        val dcs = TELEGRAM_DCS.map { it.first }
        val connects = AtomicInteger()
        val socks = fakeSocks(mapOf(dcs[0] to hex(DC2_RESPQ), dcs[1] to hex(DC2_RESPQ), dcs[2] to hex("6cfeffff"), dcs[3] to null), connects)
        val started = System.currentTimeMillis()
        val answered = runBlocking { TelegramProbeImpl(deadlineMs = 1_000).probe(socks.localPort) }
        answered shouldBe 2
        connects.get() shouldBe 3
        (System.currentTimeMillis() - started) shouldBeLessThan 1_500L
        socks.close()
    }

    test("TcpReach: закрытый порт → Down, открытый → Up") {
        val open = ServerSocket(0)
        val reach = SocketTcpReach()
        runBlocking { reach.reach(Endpoint("127.0.0.1", open.localPort), 500) } shouldBe Reach.Up
        val closedPort = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        runBlocking { reach.reach(Endpoint("127.0.0.1", closedPort), 500) } shouldBe Reach.Down
        open.close()
    }
})
