package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Обновлялка без сети: номер версии, редирект GitHub, есть ли что предложить. */
class UpdatesTest : FunSpec({

    test("версия: префикс v и суффикс сборки — не часть номера; не номер — null") {
        versionOf("v0.10.0") shouldBe Version(0, 10, 0)
        versionOf("0.8.0-dev") shouldBe Version(0, 8, 0)
        versionOf("1.2") shouldBe null
        versionOf("релиз") shouldBe null
        (Version(0, 10, 0) > Version(0, 9, 12)) shouldBe true
    }

    test("редирект releases/latest: тег — из Location; ссылки на APK и на страницу релиза — по тегу") {
        tagOf("https://github.com/PodborVPN/client/releases/tag/v0.11.0") shouldBe "v0.11.0"
        apkUrlOf("v0.11.0") shouldBe "https://github.com/PodborVPN/client/releases/download/v0.11.0/podbor-0.11.0.apk"
        releasePageOf("v0.11.0") shouldBe "https://github.com/PodborVPN/client/releases/tag/v0.11.0"
    }

    test("редирект не на тег (релизов нет), на чужой хост или тег не X.Y.Z — null") {
        tagOf("https://github.com/PodborVPN/client/releases") shouldBe null
        tagOf("https://example.org/PodborVPN/client/releases/tag/v1.0.0") shouldBe null
        tagOf("https://github.com/PodborVPN/client/releases/tag/nightly") shouldBe null
        tagOf("https://github.com/PodborVPN/client/releases/tag/v1.0.0/x") shouldBe null
        tagOf("") shouldBe null
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
})
