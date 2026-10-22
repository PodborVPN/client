package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.Endpoint
import podbor.core.Entry
import podbor.core.ProbeSpec
import podbor.core.Reach
import podbor.core.Server
import podbor.core.ServerId
import podbor.core.Transport
import podbor.core.Verdict

class ProberTest : FunSpec({
    val server = Server(ServerId("s1"), "n", Endpoint("10.0.0.1", 443), Transport.Grpc, "chrome", Entry.Unknown, "k")

    class Fakes(tcp: Reach = Reach.Up) {
        val calls = mutableListOf<String>()
        val prober = ProberImpl(
            telegram = { port -> calls += "tg:$port"; 3 },
            instagram = { port -> calls += "ig:$port"; InstagramAnswer(200, INSTAGRAM_CDN_MIN_BYTES, 200) },
            reach = { _, _ -> tcp },
        )
    }

    test("ProbeSpec.Telegram зовёт только Телеграм-пробу") {
        val f = Fakes()
        f.prober.probe(ProbeSpec.Telegram, server, 1080) shouldBe Verdict.TelegramDc(3)
        f.calls shouldBe listOf("tg:1080")
    }
    test("ProbeSpec.Instagram зовёт только пробу Инсты") {
        val f = Fakes()
        f.prober.probe(ProbeSpec.Instagram, server, 1081) shouldBe Verdict.Instagram(true, true)
        f.calls shouldBe listOf("ig:1081")
    }
    test("TCP ✗ → Unreachable для любого вида") {
        val f = Fakes(Reach.Down)
        f.prober.probe(ProbeSpec.Telegram, server, 1) shouldBe Verdict.Unreachable
        f.prober.probe(ProbeSpec.Instagram, server, 1) shouldBe Verdict.Unreachable
    }
})
