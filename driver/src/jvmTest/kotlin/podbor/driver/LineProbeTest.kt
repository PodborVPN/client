package podbor.driver

import arrow.core.Either
import arrow.core.nonEmptyListOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.types.shouldBeInstanceOf
import podbor.core.Role
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import podbor.core.Handshake
import podbor.core.Stage
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

data class Case(val name: String, val stage: Stage, val error: Throwable, val expected: Handshake) {
    override fun toString() = name
}

class LineProbeTest : FunSpec({

    withData(
        nameFn = { it.name },
        Case("таймаут connect → Silent(Connect)", Stage.Connect, SocketTimeoutException("connect timed out"), Handshake.Silent(Stage.Connect, 1_500)),
        Case("таймаут ServerHello → Silent(Tls)", Stage.Tls, SocketTimeoutException("Read timed out"), Handshake.Silent(Stage.Tls, 1_500)),
        Case("connection refused → Refused", Stage.Connect, ConnectException("Connection refused"), Handshake.Refused),
        Case("NoRouteToHost → NoRoute", Stage.Connect, NoRouteToHostException("No route to host"), Handshake.NoRoute),
        Case("network unreachable → NoRoute", Stage.Connect, ConnectException("Network is unreachable"), Handshake.NoRoute),
        Case("connection reset на Tls → Reset(Tls)", Stage.Tls, SocketException("Connection reset"), Handshake.Reset(Stage.Tls)),
        Case("remote host terminated → Reset(Tls)", Stage.Tls, SSLException("Remote host terminated the handshake"), Handshake.Reset(Stage.Tls)),
        Case("чужой сертификат → Mirage", Stage.Tls, SSLHandshakeException("bad cert").apply { initCause(CertificateException("x")) }, Handshake.Mirage),
        Case("DNS → Unknown(dns)", Stage.Connect, UnknownHostException("ya.ru"), Handshake.Unknown("dns")),
        Case("protect → Unknown(protect)", Stage.Connect, SecurityException("protect"), Handshake.Unknown("protect")),
        Case("прочее → Unknown(имя класса)", Stage.Tls, IllegalStateException("?"), Handshake.Unknown("IllegalStateException")),
    ) { c ->
        handshakeOf(c.stage, c.error, 1_500) shouldBe c.expected
    }

    fun probe(delayMs: Long = 0) = LineProbeImpl(shaker = { ref -> delay(delayMs); Handshake.Ok(connectMs = ref.label.length.toLong()) })

    test("все эталоны параллельно: раунд ≤ задержки одного + 100 мс") {
        val started = System.currentTimeMillis()
        runBlocking { probe(delayMs = 500).observe() }
        (System.currentTimeMillis() - started) shouldBeLessThan 600L
    }

    test("раунд несёт исход каждого эталона под его меткой") {
        val line = runBlocking { probe().observe() }
        line.abroad.map { it.target } shouldBe nonEmptyListOf("one.one.one.one", "dns.google", "podbor-ref")
        line.russia.map { it.target } shouldBe nonEmptyListOf("ya.ru", "vk.com", "gosuslugi.ru", "dns.yandex")
        line.abroad.map { it.handshake } shouldBe nonEmptyListOf(Handshake.Ok(15), Handshake.Ok(10), Handshake.Ok(10))
    }

    test("C1: молчащий после TCP сервер на боевых сроках → строго Silent(Tls, ≥ 1000), не ceiling") {
        java.net.ServerSocket(0).use { silent ->
            val ref = Reference("silent", "127.0.0.1", silent.localPort, "silent", Trust.System)
            val started = System.currentTimeMillis()
            val h = runBlocking { TlsHandshaker(connectMs = 1_500, ceilingMs = 1_800).shake(ref) }
            (System.currentTimeMillis() - started) shouldBeLessThan 2_100L
            val silence = h.shouldBeInstanceOf<Handshake.Silent>()
            silence.stage shouldBe Stage.Tls
            silence.waitedMs shouldBeGreaterThanOrEqual 1_000L
        }
    }

    test("потолок держится, даже когда connect блокируется: Silent(Connect) по потолку и сокет закрыт") {
        val released = java.util.concurrent.CountDownLatch(1)
        val socket = object : java.net.Socket() {
            @Volatile var closed = false
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { released.await() } // как зависший SYN
            override fun close() { closed = true; released.countDown() }
        }
        val ref = Reference("hang", "hang.invalid", 443, "hang", Trust.System)
        val started = System.currentTimeMillis()
        val h = runBlocking { TlsHandshaker(connectMs = 10_000, ceilingMs = 300, newSocket = { socket }, resolve = { java.net.InetAddress.getLoopbackAddress() }).shake(ref) }
        (System.currentTimeMillis() - started) shouldBeLessThan 500L
        h.shouldBeInstanceOf<Handshake.Silent>().stage shouldBe Stage.Connect
        socket.closed shouldBe true
    }

    test("отмена вызывающего посреди connect: отмена не глотается, сокет закрыт") {
        val released = java.util.concurrent.CountDownLatch(1)
        val socket = object : java.net.Socket() {
            @Volatile var closed = false
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { released.await() }
            override fun close() { closed = true; released.countDown() }
        }
        val ref = Reference("hang", "hang.invalid", 443, "hang", Trust.System)
        val shaker = TlsHandshaker(connectMs = 10_000, ceilingMs = 10_000, newSocket = { socket }, resolve = { java.net.InetAddress.getLoopbackAddress() })
        val started = System.currentTimeMillis()
        shouldThrow<TimeoutCancellationException> { runBlocking { withTimeout(200) { shaker.shake(ref) } } }
        (System.currentTimeMillis() - started) shouldBeLessThan 500L
        socket.closed shouldBe true
    }

    test("connectMs меряется без резолва: резолвер 400 мс, connect мгновенный -> Ok.connectMs < 100") {
        val socket = object : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) = Unit
        }
        val ref = Reference("slow-dns", "slow-dns.invalid", 443, "slow-dns", Trust.System)
        val shaker = TlsHandshaker(newSocket = { socket }, resolve = { delay(400); java.net.InetAddress.getLoopbackAddress() }, negotiate = { _, _ -> })
        val h = runBlocking { shaker.shake(ref) }
        (h as Handshake.Ok).connectMs shouldBeLessThan 100L
    }

    test("TLS молчит до потолка после медленного резолва: Silent(Tls) несёт allowedMs = остаток потолка и beforeMs = резолв+connect") {
        val released = java.util.concurrent.CountDownLatch(1)
        val socket = object : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) = Unit
            override fun close() { released.countDown() }
        }
        val ref = Reference("slow-dns", "slow-dns.invalid", 443, "slow-dns", Trust.System)
        val shaker = TlsHandshaker(
            connectMs = 1_500, ceilingMs = 1_800, newSocket = { socket },
            resolve = { delay(1_400); java.net.InetAddress.getLoopbackAddress() },
            negotiate = { _, _ -> released.await() }, // ServerHello не придёт, пока потолок не закроет сокет
        )
        val h = runBlocking { shaker.shake(ref) }.shouldBeInstanceOf<Handshake.Silent>()
        h.stage shouldBe Stage.Tls
        h.beforeMs!! shouldBeInRange 1_400L..1_550L
        h.allowedMs!! shouldBeInRange 250L..450L
        h.waitedMs shouldBeLessThanOrEqual h.allowedMs!! + 100
    }

    test("SYN без ответа после резолва 300 мс на боевых сроках: строго Silent(Connect, ≥ 1000), allowedMs = min(connectMs, остаток потолка), beforeMs ≈ резолв") {
        val released = java.util.concurrent.CountDownLatch(1)
        val socket = object : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { released.await() }
            override fun close() { released.countDown() }
        }
        val ref = Reference("blackhole", "blackhole.invalid", 443, "blackhole", Trust.System)
        val shaker = TlsHandshaker(
            connectMs = 1_500, ceilingMs = 1_800, newSocket = { socket },
            resolve = { delay(300); java.net.InetAddress.getLoopbackAddress() },
        )
        val h = runBlocking { shaker.shake(ref) }.shouldBeInstanceOf<Handshake.Silent>()
        h.stage shouldBe Stage.Connect
        h.waitedMs shouldBeGreaterThanOrEqual 1_000L
        h.beforeMs!! shouldBeInRange 300L..450L
        h.allowedMs!! shouldBeInRange 1_350L..1_500L
    }

    test("таймаут чтения ServerHello (не потолок): Silent(Tls) тоже несёт allowedMs и beforeMs") {
        val socket = object : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) = Unit
        }
        val ref = Reference("timeout", "timeout.invalid", 443, "timeout", Trust.System)
        val shaker = TlsHandshaker(
            connectMs = 1_500, ceilingMs = 1_800, newSocket = { socket },
            resolve = { java.net.InetAddress.getLoopbackAddress() },
            negotiate = { _, _ -> throw SocketTimeoutException("Read timed out") },
        )
        val h = runBlocking { shaker.shake(ref) }.shouldBeInstanceOf<Handshake.Silent>()
        h.stage shouldBe Stage.Tls
        h.beforeMs!! shouldBeLessThan 100L
        h.allowedMs!! shouldBeInRange 1_700L..1_800L
    }

    test("старый JSON Silent без allowedMs/beforeMs (Лента v0) декодируется с null") {
        kotlinx.serialization.json.Json.decodeFromString(
            Handshake.serializer(),
            """{"type":"podbor.core.Handshake.Silent","stage":"Tls","waitedMs":305}""",
        ) shouldBe Handshake.Silent(Stage.Tls, 305)
    }

    test("резолв не удался -> Unknown(dns)") {
        val shaker = TlsHandshaker(resolve = { throw UnknownHostException(it) })
        runBlocking { shaker.shake(Reference("x", "x.invalid", 443, "x", Trust.System)) } shouldBe Handshake.Unknown("dns")
    }

    test("зависший резолвер тоже укладывается в потолок") {
        val shaker = TlsHandshaker(ceilingMs = 300, resolve = { delay(10_000); java.net.InetAddress.getLoopbackAddress() })
        val started = System.currentTimeMillis()
        runBlocking { shaker.shake(Reference("x", "x.invalid", 443, "x", Trust.System)) } shouldBe Handshake.Unknown("ceiling")
        (System.currentTimeMillis() - started) shouldBeLessThan 500L
    }

    test("close() бросает — исход всё равно значение, не исключение") {
        val socket = object : java.net.Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { throw ConnectException("Connection refused") }
            override fun close() { throw java.io.IOException("close failed") }
        }
        val ref = Reference("refused", "127.0.0.1", 443, "refused", Trust.System)
        runBlocking { TlsHandshaker(newSocket = { socket }).shake(ref) } shouldBe Handshake.Refused
    }

    test("отмена пробы не глотается") {
        val hanging = LineProbeImpl(shaker = { awaitCancellation() })
        val started = System.currentTimeMillis()
        var cancelled = false
        runBlocking {
            try {
                withTimeout(200) { hanging.observe() }
            } catch (e: TimeoutCancellationException) {
                cancelled = true
            }
        }
        cancelled shouldBe true
        (System.currentTimeMillis() - started) shouldBeLessThan 300L
    }

    /** Локальный TLS-сервер с самоподписанным сертификатом CN/SAN podbor-test (test resources, line-test.p12). */
    fun <T> withTlsServer(body: (port: Int, sha256: String) -> T): T {
        val pass = "podbor".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            LineProbeTest::class.java.getResourceAsStream("/line-test.p12").use { load(it, pass) }
        }
        val sha = sha256Hex(store.getCertificate("line").encoded)
        val ctx = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, pass) }.keyManagers, null, null)
        }
        return (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
            thread(isDaemon = true) {
                // Принимаем по одному; провал рукопожатия на стороне сервера — норма для Mirage-случаев.
                generateSequence { Either.catch { server.accept() as SSLSocket }.getOrNull() }.forEach { client ->
                    Either.catch { client.use { it.startHandshake(); it.inputStream.read() } }
                }
            }
            body(server.localPort, sha)
        }
    }

    fun tlsRef(port: Int, trust: Trust) = Reference("local", "127.0.0.1", port, "podbor-test", trust)

    test("T2: Pinned с отпечатком сертификата сервера → Ok (боевой startTls)") {
        withTlsServer { port, sha -> runBlocking { TlsHandshaker().shake(tlsRef(port, Trust.Pinned(sha))) } }
            .shouldBeInstanceOf<Handshake.Ok>()
    }

    test("T2: Pinned с чужим отпечатком → Mirage") {
        withTlsServer { port, _ -> runBlocking { TlsHandshaker().shake(tlsRef(port, Trust.Pinned(sha256Hex("other".toByteArray())))) } } shouldBe Handshake.Mirage
    }

    test("T2: System против самоподписанного → Mirage (цепочка не доверена)") {
        withTlsServer { port, _ -> runBlocking { TlsHandshaker().shake(tlsRef(port, Trust.System)) } } shouldBe Handshake.Mirage
    }

    test("свой эталон podbor-ref несёт роль Own, CDN — Public") {
        val line = runBlocking { probe().observe() }
        line.abroad.map { it.role } shouldBe nonEmptyListOf(Role.Public, Role.Public, Role.Own)
        line.russia.map { it.role } shouldBe nonEmptyListOf(Role.Public, Role.Public, Role.Public, Role.Public)
    }

    test("прикол сертификата: отпечаток сверяется без учёта регистра и двоеточий") {
        val der = "abc".toByteArray()
        pinMatches(sha256Hex(der), der) shouldBe true
        pinMatches(sha256Hex(der).uppercase().chunked(2).joinToString(":"), der) shouldBe true
        pinMatches(sha256Hex(der), "abd".toByteArray()) shouldBe false
    }
})
