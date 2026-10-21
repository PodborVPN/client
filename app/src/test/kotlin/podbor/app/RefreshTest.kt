package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.sub.DEFAULT_UPDATE_HOURS
import podbor.app.sub.Fetched
import podbor.app.sub.Subscription
import podbor.app.sub.fetched
import podbor.app.sub.refreshDue
import podbor.app.sub.updateHoursOf

private const val HOUR = 3_600_000L

class RefreshTest : FunSpec({
    test("интервал провайдера: заголовок ответа или шапка тела; заголовок важнее; границы 1–24 ч") {
        updateHoursOf(Fetched("vless://a", mapOf("profile-update-interval" to " 6 "))) shouldBe 6
        updateHoursOf(Fetched("# profile-title: X\n# profile-update-interval: 1\n# Date: today\nvless://a")) shouldBe 1
        updateHoursOf(Fetched("#profile-update-interval:3\nvless://a", mapOf("profile-update-interval" to "2"))) shouldBe 2
        updateHoursOf(Fetched("vless://a", mapOf("profile-update-interval" to "0"))) shouldBe 1
        updateHoursOf(Fetched("vless://a", mapOf("profile-update-interval" to "168"))) shouldBe 24
    }
    test("интервала нет или он не число; строка после первого сервера — не шапка") {
        updateHoursOf(Fetched("vless://a")) shouldBe null
        updateHoursOf(Fetched("vless://a", mapOf("profile-update-interval" to "часто"))) shouldBe null
        updateHoursOf(Fetched("vless://a\n# profile-update-interval: 1")) shouldBe null
    }
    test("пора обновить: только по ссылке; ни разу не качали — пора; интервал провайдера или 12 ч") {
        refreshDue(Subscription(null, "t", fetchedAtMs = 0), 100 * HOUR) shouldBe false
        refreshDue(Subscription("https://a.b", "t"), 0) shouldBe true
        refreshDue(Subscription("https://a.b", "t", fetchedAtMs = 0, updateHours = 1), HOUR - 1) shouldBe false
        refreshDue(Subscription("https://a.b", "t", fetchedAtMs = 0, updateHours = 1), HOUR) shouldBe true
        refreshDue(Subscription("https://a.b", "t", fetchedAtMs = 0), DEFAULT_UPDATE_HOURS * HOUR - 1) shouldBe false
        refreshDue(Subscription("https://a.b", "t", fetchedAtMs = 0), DEFAULT_UPDATE_HOURS * HOUR) shouldBe true
    }
    test("скачали: текст и время новые; интервала в ответе нет — прежний (ответ прокси Яндекса без заголовков)") {
        Subscription("https://a.b", "старый", 0, 3).fetched(Fetched("новый"), 5) shouldBe Subscription("https://a.b", "новый", 5, 3)
        Subscription("https://a.b", "старый", 0, 3).fetched(Fetched("новый", mapOf("profile-update-interval" to "1")), 5) shouldBe
            Subscription("https://a.b", "новый", 5, 1)
    }
})
