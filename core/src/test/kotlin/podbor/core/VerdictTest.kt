package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
})
