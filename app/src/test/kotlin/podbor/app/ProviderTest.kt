package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.sub.Fetched
import podbor.app.sub.ProviderInfo
import podbor.app.sub.Subscription
import podbor.screen.Announce
import podbor.screen.Support
import podbor.screen.SubscriptionNote
import podbor.app.sub.fetched
import podbor.app.sub.noteOf
import podbor.app.sub.providerOf
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

private const val GB = 1_073_741_824L
private const val HOUR = 3_600_000L

class ProviderTest : FunSpec({
    test("заголовки панели: имя (base64), трафик и срок, поддержка, объявление") {
        val title = "base64:" + Base64.getEncoder().encodeToString("🇳🇱 Мой VPN".toByteArray())
        val f = Fetched(
            "vless://a",
            mapOf(
                "profile-title" to title,
                "subscription-userinfo" to "upload=1073741824; download=2147483648; total=107374182400; expire=1764460800",
                "support-url" to "https://t.me/support",
                "announce" to "Оплата до пятницы",
            ),
        )
        providerOf(f) shouldBe ProviderInfo(
            "🇳🇱 Мой VPN", 3 * GB, 100 * GB, 1764460800, Support.Link("https://t.me/support"), Announce.Text("Оплата до пятницы"),
        )
    }
    test("шапка тела: имя; ничего не сказал — пусто; поддержка не ссылкой — нет") {
        providerOf(Fetched("# profile-title: БЕЛЫЕ СПИСКИ\n# profile-update-interval: 1\nvless://a")) shouldBe ProviderInfo(title = "БЕЛЫЕ СПИСКИ")
        providerOf(Fetched("vless://a")) shouldBe ProviderInfo()
        providerOf(Fetched("vless://a", mapOf("support-url" to "javascript:alert(1)"))) shouldBe ProviderInfo()
        providerOf(Fetched("vless://a", mapOf("subscription-userinfo" to "мусор"))) shouldBe ProviderInfo()
    }
    test("скачали через прокси (без заголовков) — сведения прежние; сказал новое — новые") {
        val old = Subscription("https://a.b", "t", provider = ProviderInfo(title = "X"))
        old.fetched(Fetched("новый"), 5).provider shouldBe ProviderInfo(title = "X")
        old.fetched(Fetched("новый", mapOf("announce" to "Привет")), 5).provider shouldBe ProviderInfo(announce = Announce.Text("Привет"))
    }
    test("слова: свежесть, срок, трафик") {
        val now = 1764460800_000L - 24 * HOUR
        val s = Subscription(
            "https://a.b", "t", fetchedAtMs = now - 2 * HOUR,
            provider = ProviderInfo(
                "Мой VPN", usedBytes = 3 * GB / 2, totalBytes = 100 * GB, expireS = 1764460800,
                support = Support.Link("https://t.me/s"), announce = Announce.Text("Привет"),
            ),
        )
        val day = LocalDate.of(2025, 11, 30).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        noteOf(s, now, ZoneOffset.UTC) shouldBe SubscriptionNote(
            "Мой VPN", listOf("обновлена 2 ч назад", "оплачена до $day", "потрачено 1,5 ГБ из 100 ГБ"),
            Support.Link("https://t.me/s"), Announce.Text("Привет"),
        )
    }
    test("слова: текстом — не обновляется; срок вышел; без лимита; давно и только что") {
        noteOf(Subscription(null, "t"), 0).lines shouldBe listOf("добавлена текстом — сама не обновляется")
        noteOf(Subscription("https://a.b", "t"), 0).lines shouldBe emptyList()
        val p = ProviderInfo(usedBytes = 12 * GB, totalBytes = 0, expireS = 1)
        noteOf(Subscription("https://a.b", "t", fetchedAtMs = 0, provider = p), 3 * 24 * HOUR, ZoneOffset.UTC).lines shouldBe
            listOf("обновлена 3 дн назад", "оплачена до 01.01.1970 — срок вышел", "потрачено 12 ГБ")
        noteOf(Subscription("https://a.b", "t", fetchedAtMs = 0), 30_000).lines shouldBe listOf("обновлена только что")
    }
})
