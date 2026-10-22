package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WhiteListsTest : FunSpec({

    val lists = WhiteLists.of(
        cidrs = listOf("203.0.113.0/24", "2a00:1fa0::/32", "мусор", "1.2.3.4/99", "1.2.3/24", "", "5.6.7.8", "::1/x"),
        domains = listOf("ya.ru", "", "  VK.com "),
    )

    test("whiteHint: Likely ⟺ IP входа в белых CIDR (белые у операторов — по адресу), SNI не условие") {
        whiteHint(lists, "203.0.113.1") shouldBe WhiteHint.Likely
        whiteHint(lists, "198.51.100.1") shouldBe WhiteHint.Unlikely
        whiteHint(lists, "ya.ru") shouldBe WhiteHint.Unlikely
    }
    test("whiteHint по IPv6, включая сжатие ::") {
        whiteHint(lists, "2a00:1fa0:1::1") shouldBe WhiteHint.Likely
        whiteHint(lists, "2a00:1fa1::1") shouldBe WhiteHint.Unlikely
    }
    test("одиночный адрес без маски — /32; мусор пропускается") {
        whiteHint(lists, "5.6.7.8") shouldBe WhiteHint.Likely
        whiteHint(lists, "1.2.3.4") shouldBe WhiteHint.Unlikely
        whiteHint(lists, "не ip") shouldBe WhiteHint.Unlikely
    }
    test("sniWhite: домен и поддомены, не суффикс строки; регистр не важен; нет SNI — нет") {
        sniWhite(lists, "ya.ru") shouldBe true
        sniWhite(lists, "api.ya.ru") shouldBe true
        sniWhite(lists, "notya.ru") shouldBe false
        sniWhite(lists, "API.VK.com") shouldBe true
        sniWhite(lists, "evil.com") shouldBe false
        sniWhite(lists, null) shouldBe false
    }
})
