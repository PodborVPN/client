package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.Reach
import podbor.core.Verdict

/** Подписи из замера: настоящий TlsSilence на :8443 и контроль :2096. */
class ClassifyTest : FunSpec({
    test("TCP до сервера не прошёл → Unreachable, что бы ни ответила проба") {
        classify(answered = 0, tcp = Reach.Down) shouldBe Verdict.Unreachable
        classify(answered = 3, tcp = Reach.Down) shouldBe Verdict.Unreachable
    }
    test("TCP прошёл, ни одного DC → TlsSilence (залп ios на :8443)") {
        classify(answered = 0, tcp = Reach.Up) shouldBe Verdict.TlsSilence
    }
    test("TCP прошёл, n DC → TelegramDc(n) (контроль :2096)") {
        classify(answered = 3, tcp = Reach.Up) shouldBe Verdict.TelegramDc(3)
        classify(answered = 1, tcp = Reach.Up) shouldBe Verdict.TelegramDc(1)
    }
    test("Инста: CDN ≥ 96 КБ и www ответил → оба ok") {
        classifyInstagram(InstagramAnswer(200, 98304, 302), Reach.Up) shouldBe Verdict.Instagram(cdnOk = true, wwwOk = true)
    }
    test("Инста: 16 КБ CDN — заморозка, cdnOk = false") {
        classifyInstagram(InstagramAnswer(200, 16384, 200), Reach.Up) shouldBe Verdict.Instagram(cdnOk = false, wwwOk = true)
    }
    test("Инста: тишина при TCP ✓ — не TlsSilence (блок IP:порт ставит только Телега)") {
        classifyInstagram(InstagramAnswer(null, 0, null), Reach.Up) shouldBe Verdict.Instagram(cdnOk = false, wwwOk = false)
    }
    test("Инста: TCP ✗ → Unreachable") {
        classifyInstagram(InstagramAnswer(200, 98304, 200), Reach.Down) shouldBe Verdict.Unreachable
    }
    test("Инста: www 429 — ответил сам, 403 — нет") {
        classifyInstagram(InstagramAnswer(200, 98304, 429), Reach.Up) shouldBe Verdict.Instagram(true, true)
        classifyInstagram(InstagramAnswer(200, 98304, 403), Reach.Up) shouldBe Verdict.Instagram(true, false)
    }
})
