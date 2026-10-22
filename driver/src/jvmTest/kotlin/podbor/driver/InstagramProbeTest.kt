package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Фейковый SOCKS5, за которым сразу HTTP: путь /www — 302, /drip — 200 и капля по 512 байт раз в 50 мс, /mute — молчит. */
private fun fakeSocksHttp(): ServerSocket {
    val server = ServerSocket(0)
    thread(isDaemon = true) {
        while (!server.isClosed) {
            val c = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { serveHttp(c) } }
        }
    }
    return server
}

private fun serveHttp(c: Socket) = c.use {
    val i = it.getInputStream(); val o = it.getOutputStream()
    val methods = i.readNBytes(2)[1].toInt(); i.readNBytes(methods); o.write(byteArrayOf(5, 0))
    val head = i.readNBytes(4)
    when (head[3].toInt()) {
        1 -> i.readNBytes(4 + 2)
        3 -> i.readNBytes((i.read() and 0xff) + 2)
        else -> i.readNBytes(16 + 2)
    }
    o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
    val path = i.requestLine().split(' ')[1]
    when (path) {
        "/www" -> o.write("HTTP/1.1 302 Found\r\nLocation: /x\r\nContent-Length: 0\r\n\r\n".toByteArray())
        "/drip" -> {
            o.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".toByteArray()); o.flush()
            repeat(2_000) { o.write(ByteArray(512)); o.flush(); Thread.sleep(50) }
        }
        else -> Thread.sleep(20_000)
    }
    o.flush()
}

/** Первая строка запроса; заголовки дочитываются до пустой строки. */
private fun InputStream.requestLine(): String {
    val lines = generateSequence { readLine() }.takeWhile { it.isNotEmpty() }.toList()
    return lines.first()
}

private fun InputStream.readLine(): String {
    val sb = StringBuilder()
    while (true) {
        val b = read()
        if (b < 0 || b == '\n'.code) break
        if (b != '\r'.code) sb.append(b.toChar())
    }
    return sb.toString()
}

class InstagramProbeTest : FunSpec({

    test("медленная капля CDN не держит пробу дольше срока: частичные байты, cdn не годен") {
        fakeSocksHttp().use { socks ->
            val probe = InstagramProbeImpl(deadlineMs = 1_000, cdnUrl = "http://127.0.0.1/drip", wwwUrl = "http://127.0.0.1/www")
            val started = System.currentTimeMillis()
            val answer = runBlocking { probe.probe(socks.localPort) }
            (System.currentTimeMillis() - started) shouldBeLessThan 1_500L
            answer.cdnStatus shouldBe 200
            answer.cdnBytes shouldBeLessThan INSTAGRAM_CDN_MIN_BYTES
            answer.wwwStatus shouldBe 302
            classifyInstagram(answer, podbor.core.Reach.Up) shouldBe podbor.core.Verdict.Instagram(cdnOk = false, wwwOk = true)
        }
    }

    test("отмена прерывает заблокированное чтение ответа сразу, а не по его таймауту") {
        fakeSocksHttp().use { socks ->
            val probe = InstagramProbeImpl(deadlineMs = 10_000, cdnUrl = "http://127.0.0.1/mute", wwwUrl = "http://127.0.0.1/mute")
            var returned = false
            val started = System.currentTimeMillis()
            runBlocking {
                val job = launch(start = CoroutineStart.UNDISPATCHED) { probe.probe(socks.localPort); returned = true }
                delay(300)
                job.cancel()
                job.join()
            }
            (System.currentTimeMillis() - started) shouldBeLessThan 1_500L
            returned shouldBe false
        }
    }
})
