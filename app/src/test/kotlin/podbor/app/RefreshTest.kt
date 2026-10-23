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

    test("переезд: new-url — новая ссылка целиком; new-domain — только домен, путь и токен прежние") {
        val s = Subscription("https://old.example/sub/T0k?f=1", "старый")
        s.fetched(Fetched("новый", mapOf("new-url" to "https://new.example/s/X")), 5).link shouldBe "https://new.example/s/X"
        s.fetched(Fetched("новый", mapOf("new-domain" to "new.example")), 5).link shouldBe "https://new.example/sub/T0k?f=1"
        // Шапкой в теле — так же; заголовок важнее шапки.
        s.fetched(Fetched("#new-domain: body.example\nvless://a"), 5).link shouldBe "https://body.example/sub/T0k?f=1"
        s.fetched(Fetched("#new-url: https://body.example/x\nvless://a", mapOf("new-url" to "https://head.example/y")), 5).link shouldBe
            "https://head.example/y"
    }

    test("переезд: не http(s), не адрес, домен с путём или мусором — ссылка прежняя") {
        val s = Subscription("https://old.example/sub/T0k", "старый")
        listOf(
            mapOf("new-url" to "javascript:alert(1)"),
            mapOf("new-url" to "не ссылка"),
            mapOf("new-domain" to "evil.example/path"),
            mapOf("new-domain" to "a b"),
            mapOf("new-domain" to ""),
        ).forEach { h -> s.fetched(Fetched("новый", h), 5).link shouldBe "https://old.example/sub/T0k" }
    }

    test("запасная ссылка: из ответа запоминается, нет её в новом ответе — прежняя; не http(s) — не берём") {
        val s = Subscription("https://a.example/s", "старый")
        val withFallback = s.fetched(Fetched("новый", mapOf("fallback-url" to "https://b.example/s")), 5)
        withFallback.fallbackUrl shouldBe "https://b.example/s"
        withFallback.fetched(Fetched("ещё новее"), 6).fallbackUrl shouldBe "https://b.example/s"
        s.fetched(Fetched("новый", mapOf("fallback-url" to "ftp://b.example/s")), 5).fallbackUrl shouldBe null
    }
})
