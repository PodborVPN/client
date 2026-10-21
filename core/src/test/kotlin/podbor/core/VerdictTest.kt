package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class VerdictTest : FunSpec({
    test("Instagram: Fits только когда ответили и CDN, и www") {
        Verdict.Instagram(cdnOk = true, wwwOk = true).kind() shouldBe VerdictKind.Fits
        Verdict.Instagram(cdnOk = true, wwwOk = false).kind() shouldBe VerdictKind.ServiceDead
        Verdict.Instagram(cdnOk = false, wwwOk = true).kind() shouldBe VerdictKind.ServiceDead
        Verdict.Instagram(cdnOk = false, wwwOk = false).kind() shouldBe VerdictKind.ServiceDead
    }

    test("TelegramDc: 2 DC — Fits, 1 DC — ServiceDead") {
        Verdict.TelegramDc(2).kind() shouldBe VerdictKind.Fits
        Verdict.TelegramDc(1).kind() shouldBe VerdictKind.ServiceDead
    }

    test("Deed.Probe на проводе называется как старое ProbeTelegram — Ленты v1 читаются") {
        val json = Json { encodeDefaults = true }
        val line = json.encodeToString(Deed.serializer(), Deed.Probe(ServerId("s001")))
        line shouldBe """{"type":"podbor.core.Deed.ProbeTelegram","server":"s001"}"""
        json.decodeFromString(Deed.serializer(), line) shouldBe Deed.Probe(ServerId("s001"))
    }
})
