package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * Фейковый SOCKS5, за которым сразу HTTP-ответ [reply] (после запроса); [tls] — оборачивает соединение в TLS-сервер
 * (самоподписанный эталон); reply == null — молчит.
 */
private fun fakeExitServer(reply: String?, tls: SSLContext? = null): ServerSocket {
    val server = ServerSocket(0)
    thread(isDaemon = true) {
        while (!server.isClosed) {
            val c = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { runCatching { serveExit(c, reply, tls) } }
        }
    }
    return server
}

private fun serveExit(c: Socket, reply: String?, tls: SSLContext?) = c.use {
    val i = it.getInputStream(); val o = it.getOutputStream()
    val methods = i.readNBytes(2)[1].toInt(); i.readNBytes(methods); o.write(byteArrayOf(5, 0))
    val head = i.readNBytes(4)
    when (head[3].toInt()) {
        1 -> i.readNBytes(4 + 2)
        3 -> i.readNBytes((i.read() and 0xff) + 2)
        else -> i.readNBytes(16 + 2)
    }
    o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
    val s = tls?.let { ctx ->
        (ctx.socketFactory.createSocket(it, null, it.port, false) as SSLSocket).apply { useClientMode = false }
    } ?: it
    val si = s.getInputStream()
    while (true) { // заголовки запроса до пустой строки
        val line = StringBuilder()
        while (true) { val b = si.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) line.append(b.toChar()) }
        if (line.isEmpty()) break
    }
    if (reply == null) Thread.sleep(20_000)
    else { s.getOutputStream().write(reply.toByteArray()); s.getOutputStream().flush() }
}

private fun ok(body: String) = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"

/** Самоподписанный сертификат через keytool JDK: контекст сервера и отпечаток листа в формате эталона. */
private fun selfSigned(): Pair<SSLContext, String> {
    val dir = Files.createTempDirectory("ref").toFile().apply { deleteOnExit() }
    val ks = File(dir, "ks.p12")
    val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
    val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "ref", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=podbor-ref",
        "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit").redirectErrorStream(true).start()
    check(p.waitFor() == 0) { p.inputStream.readAllBytes().decodeToString() }
    val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
    val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("ref").encoded).joinToString("") { "%02X".format(it) }
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) } to pin
}

class ExitProbeTest : FunSpec({

    test("разбор тела: IP и перевод строки обрезаются, мусор — null") {
        exitIpOf("1.2.3.4\n") shouldBe "1.2.3.4"
        exitIpOf(" 2001:db8::1\r\n") shouldBe "2001:db8::1"
        exitIpOf("<html>nginx</html>") shouldBe null
        exitIpOf("999.1.1.1") shouldBe null
        exitIpOf("") shouldBe null
    }

    test("ответ эталона через SOCKS кандидата — IP выхода") {
        fakeExitServer(ok("203.0.113.7\n")).use { socks ->
            runBlocking { ExitProbeImpl(url = "http://195.133.26.5/ip").exitIp(socks.localPort) } shouldBe "203.0.113.7"
        }
    }

    test("не 200 и мусор в теле — null") {
        fakeExitServer("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").use { socks ->
            runBlocking { ExitProbeImpl(url = "http://195.133.26.5/ip").exitIp(socks.localPort) } shouldBe null
        }
        fakeExitServer(ok("<html>captive</html>")).use { socks ->
            runBlocking { ExitProbeImpl(url = "http://195.133.26.5/ip").exitIp(socks.localPort) } shouldBe null
        }
    }

    test("молчащий эталон не держит пробу дольше срока") {
        fakeExitServer(null).use { socks ->
            val started = System.currentTimeMillis()
            runBlocking { ExitProbeImpl(deadlineMs = 500, url = "http://195.133.26.5/ip").exitIp(socks.localPort) } shouldBe null
            (System.currentTimeMillis() - started) shouldBeLessThan 1_000L
        }
    }

    test("TLS: сервер принял TCP и молчит в рукопожатии — null в пределах срока") {
        fakeExitServer(null).use { socks ->
            val started = System.currentTimeMillis()
            runBlocking { ExitProbeImpl(deadlineMs = 500).exitIp(socks.localPort) } shouldBe null
            (System.currentTimeMillis() - started) shouldBeLessThan 800L
        }
    }

    test("сырой ответ: только 200 с IP в теле") {
        exitIpOfResponse("HTTP/1.0 200 OK\r\nX: y\r\n\r\n9.9.9.9\n") shouldBe "9.9.9.9"
        exitIpOfResponse("HTTP/1.0 502 Bad\r\n\r\n9.9.9.9") shouldBe null
        exitIpOfResponse("HTTP/1.0 200 OK\r\n") shouldBe null
    }

    test("TLS: сертификат с совпавшим отпечатком принят, с чужим — null") {
        val (ctx, pin) = selfSigned()
        fakeExitServer(ok("198.51.100.9\n"), ctx).use { socks ->
            runBlocking { ExitProbeImpl(pin = pin).exitIp(socks.localPort) } shouldBe "198.51.100.9"
        }
        fakeExitServer(ok("198.51.100.9\n"), ctx).use { socks ->
            runBlocking { ExitProbeImpl(pin = "00".repeat(32)).exitIp(socks.localPort) } shouldBe null
        }
    }
})
