package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import podbor.core.Exit

class ExitDiscoveryTest : FunSpec({
    test("сетевой ответ HTTPS не требует страны выхода") {
        val body = "ip=203.0.113.8\nloc=XX\n"
        val reply = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        fakeExitServer(reply).use { socks ->
            val trace = TraceExit("http://example.com/cdn-cgi/trace")
            runBlocking { trace.responds(socks.localPort) } shouldBe true
            runBlocking { trace.measure(socks.localPort) } shouldBe Exit.Unknown
        }
    }

    test("запасное измерение идёт через SOCKS кандидата и разбирает HTTP-ответ") {
        val body = "ip=203.0.113.8\nloc=NL\n"
        fakeExitServer("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").use { socks ->
            runBlocking { TraceExit("http://example.com/cdn-cgi/trace").measure(socks.localPort) } shouldBe Exit.At("203.0.113.8", "NL")
        }
    }
    test("запасной эталон с бесконечным телом ограничен размером") {
        val body = "ip=203.0.113.8\nloc=NL\n" + "x".repeat(5000)
        fakeExitServer("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").use { socks ->
            runBlocking { TraceExit("http://example.com/cdn-cgi/trace").measure(socks.localPort) } shouldBe Exit.Unknown
        }
    }
    test("запасное измерение молчащего эталона завершается по сроку") {
        fakeExitServer(null).use { socks ->
            val start = System.nanoTime()
            runBlocking { TraceExit("http://example.com/cdn-cgi/trace", 100).measure(socks.localPort) } shouldBe Exit.Unknown
            ((System.nanoTime() - start) / 1_000_000 < 1000) shouldBe true
        }
    }

    test("локальная страна не требует внешнего запасного запроса") {
        var backups = 0
        val probe = ExitDiscovery(ExitProbe { "203.0.113.1" }, { "NL" }, { backups++; Exit.Unknown })
        runBlocking { probe.measure(20000) } shouldBe Exit.At("203.0.113.1", "NL")
        backups shouldBe 0
    }
    test("молчащий эталон повторяется затем используется независимый ответ") {
        var attempts = 0
        val probe = ExitDiscovery(ExitProbe { attempts++; null }, { null }, { Exit.At("203.0.113.2", "DE") })
        runBlocking { probe.measure(20000) } shouldBe Exit.At("203.0.113.2", "DE")
        attempts shouldBe 2
    }
    test("IP без локальной страны уточняется запасным измерением") {
        val probe = ExitDiscovery(ExitProbe { "203.0.113.1" }, { null }, { Exit.At("203.0.113.2", "NL") })
        runBlocking { probe.measure(20000) } shouldBe Exit.At("203.0.113.2", "NL")
    }
    test("оба источника молчат — неизвестный выход") {
        val probe = ExitDiscovery(ExitProbe { null }, { null }, { Exit.Unknown })
        runBlocking { probe.measure(20000) } shouldBe Exit.Unknown
    }
    test("отмена не превращается в неизвестный выход") {
        var cancelled = false
        try {
            runBlocking { ExitDiscovery(ExitProbe { throw CancellationException() }, { null }, { Exit.Unknown }).measure(20000) }
        } catch (_: CancellationException) { cancelled = true }
        cancelled shouldBe true
    }
    test("разбор независимого ответа не принимает неполную геолокацию") {
        exitOfTrace("ip=203.0.113.8\nloc=NL\n") shouldBe Exit.At("203.0.113.8", "NL")
        exitOfTrace("ip=203.0.113.8\nloc=XX\n") shouldBe Exit.Unknown
        exitOfTrace("ip=bad\nloc=NL\n") shouldBe Exit.Unknown
        exitOfTrace("ip=203.0.113.8\n") shouldBe Exit.Unknown
    }
})
