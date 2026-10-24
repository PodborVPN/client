package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import podbor.core.ConnSample
import podbor.core.Conns
import podbor.core.Dest

/** Журнал соединений Xray: строка access-лога → окна «пара × назначение × сколько» → Лента; только пары сервисов. */
class AccessLogTest : FunSpec({

    val dc = "2026/10/24 14:20:36.366630 from tcp:127.0.0.1:57360 accepted tcp:149.154.167.51:443 [tun -> pair.tg.s001]"

    test("строка access-лога Xray: назначение, вход, выход; балансер пишет и `>>`; домен и IPv6 — тоже назначение") {
        accessLineOf(dc) shouldBe AccessLine.Accepted(Dest("tcp", "149.154.167.51", 443), "tun", "pair.tg.s001")
        accessLineOf("2026/10/24 14:20:36.375470 from tcp:127.0.0.1:48934 accepted udp:203.0.113.7:443 [tun >> pair.insta.s002]") shouldBe
            AccessLine.Accepted(Dest("udp", "203.0.113.7", 443), "tun", "pair.insta.s002")
        accessLineOf("2026/10/24 14:20:36.375470 from 127.0.0.1:1 accepted tcp:example.org:443 [tun -> exit] email: a@b") shouldBe
            AccessLine.Accepted(Dest("tcp", "example.org", 443), "tun", "exit")
        accessLineOf("2026/10/24 14:20:36.375470 from tcp:[::1]:1 accepted tcp:[2001:db8::1]:443 [tun -> pair.tg.s001]") shouldBe
            AccessLine.Accepted(Dest("tcp", "2001:db8::1", 443), "tun", "pair.tg.s001")
    }

    test("не соединение (отказ, мусор, пусто) — Other") {
        accessLineOf("2026/10/24 14:20:36.375470 from tcp:127.0.0.1:1 rejected  proxy/socks: unknown command") shouldBe AccessLine.Other
        accessLineOf("мусор") shouldBe AccessLine.Other
        accessLineOf("") shouldBe AccessLine.Other
    }

    test("окно: только выходы пар сервисов (остальной трафик человека не пишем), счёт по паре и назначению") {
        val lines = listOf(
            dc, dc, dc,
            "2026/10/24 14:20:37.0 from tcp:127.0.0.1:1 accepted tcp:149.154.175.50:443 [tun -> pair.tg.s001]",
            "2026/10/24 14:20:37.0 from tcp:127.0.0.1:1 accepted tcp:example.org:443 [tun -> exit]",
            "2026/10/24 14:20:37.0 from tcp:127.0.0.1:1 accepted tcp:203.0.113.9:443 [tun -> direct]",
            "мусор",
        ).map(::accessLineOf)
        connsOf(lines) shouldBe listOf(
            Conns("pair.tg.s001", Dest("tcp", "149.154.167.51", 443), 3),
            Conns("pair.tg.s001", Dest("tcp", "149.154.175.50", 443), 1),
        )
        connsOf(listOf(accessLineOf("2026/10/24 14:20:37.0 from tcp:127.0.0.1:1 accepted tcp:example.org:443 [tun -> exit]"))).shouldBeEmpty()
    }

    test("цикл: окно раз в CONN_WINDOW_MS — в Ленту и наружу; пустое окно не пишется") {
        runTest {
            val lines = Channel<String>(Channel.UNLIMITED)
            val tape = mutableListOf<String>()
            val seen = mutableListOf<ConnSample>()
            val job = backgroundScope.launch {
                connLoop(lines, clock = Clock { 1_000 + testScheduler.currentTime }, mono = { testScheduler.currentTime }, epoch = 3,
                    tape = TapeSink { tape += it }, emit = { seen += it })
            }
            lines.send(dc); lines.send(dc)
            runCurrent()
            advanceTimeBy(CONN_WINDOW_MS + 1)
            runCurrent()
            seen.single() shouldBe ConnSample(3, 1_000 + CONN_WINDOW_MS, CONN_WINDOW_MS, CONN_WINDOW_MS, listOf(Conns("pair.tg.s001", Dest("tcp", "149.154.167.51", 443), 2)))
            rowOf(tape.single()) shouldBe Row.Conns(seen.single())
            advanceTimeBy(3 * CONN_WINDOW_MS)
            runCurrent()
            seen.size shouldBe 1
            tape.size shouldBe 1
            job.cancel()
        }
    }
})
