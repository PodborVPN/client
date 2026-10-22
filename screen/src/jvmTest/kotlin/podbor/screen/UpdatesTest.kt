package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Обновлялка без сети: номер версии, ответ GitHub, есть ли что предложить, пора ли спрашивать. */
class UpdatesTest : FunSpec({

    test("версия: префикс v и суффикс сборки — не часть номера; не номер — null") {
        versionOf("v0.10.0") shouldBe Version(0, 10, 0)
        versionOf("0.8.0-dev") shouldBe Version(0, 8, 0)
        versionOf("1.2") shouldBe null
        versionOf("релиз") shouldBe null
        (Version(0, 10, 0) > Version(0, 9, 12)) shouldBe true
    }

    test("ответ GitHub: тег и ссылка на APK из assets; APK нет — страница релиза") {
        val json = """{"tag_name":"v0.10.0","html_url":"https://github.com/PodborVPN/client/releases/tag/v0.10.0",
            "assets":[{"name":"podbor-0.10.0.apk.sha256","browser_download_url":"https://example.org/a.sha256"},
                      {"name":"podbor-0.10.0.apk","browser_download_url":"https://example.org/podbor-0.10.0.apk"}]}"""
        releaseOf(json) shouldBe Release("v0.10.0", "https://example.org/podbor-0.10.0.apk")
        releaseOf("""{"tag_name":"v0.10.0","html_url":"https://example.org/rel","assets":[]}""") shouldBe Release("v0.10.0", "https://example.org/rel")
    }

    test("ответ GitHub без тега, битый или чужой ссылкой — null") {
        releaseOf("""{"message":"Not Found"}""") shouldBe null
        releaseOf("<html>") shouldBe null
        releaseOf("""{"tag_name":"v1.0.0","html_url":"javascript:alert(1)","assets":[]}""") shouldBe null
    }

    test("предложение — только если на GitHub новее; та же, старее или непонятная — нет") {
        val r = Release("v0.10.0", "https://example.org/podbor-0.10.0.apk")
        offerOf("0.8.0", r) shouldBe Offer("0.10.0", "https://example.org/podbor-0.10.0.apk")
        offerOf("0.8.0-dev", r) shouldBe Offer("0.10.0", "https://example.org/podbor-0.10.0.apk")
        offerOf("0.10.0", r) shouldBe null
        offerOf("0.11.0", r) shouldBe null
        offerOf("0.8.0", Release("nightly", "https://example.org/x")) shouldBe null
        offerOf("0.8.0", null) shouldBe null
    }

    test("спрашивать GitHub — не чаще раза в час; ни разу не спрашивали — пора") {
        checkDue(lastMs = null, nowMs = 0) shouldBe true
        checkDue(lastMs = 0, nowMs = 3_600_000L - 1) shouldBe false
        checkDue(lastMs = 0, nowMs = 3_600_000L) shouldBe true
        // Часы ушли назад — спросить.
        checkDue(lastMs = 10_000, nowMs = 0) shouldBe true
    }
})
