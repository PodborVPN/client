package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class WhiteListsTest : FunSpec({

    val lists = WhiteLists.of(
        cidrs = listOf("203.0.113.0/24", "2a00:1fa0::/32", "мусор", "1.2.3.4/99", "1.2.3/24", "", "5.6.7.8", "::1/x"),
        domains = listOf("ya.ru", "", "  VK.com "),
    )

    test("IPv4 по CIDR") {
        lists.hasIp("203.0.113.10") shouldBe true
        lists.hasIp("198.51.100.1") shouldBe false
    }
    test("IPv6 по CIDR, включая сжатие ::") {
        lists.hasIp("2a00:1fa0:1::1") shouldBe true
        lists.hasIp("2a00:1fa1::1") shouldBe false
        lists.hasIp("203.0.113.10") shouldBe true
    }
    test("одиночный адрес без маски — /32; мусор пропускается") {
        lists.hasIp("5.6.7.8") shouldBe true
        lists.hasIp("1.2.3.4") shouldBe false
        lists.hasIp("не ip") shouldBe false
    }
    test("SNI: домен и поддомены, не суффикс строки") {
        lists.hasSni("ya.ru") shouldBe true
        lists.hasSni("api.ya.ru") shouldBe true
        lists.hasSni("notya.ru") shouldBe false
        lists.hasSni("API.VK.com") shouldBe true
    }
    test("whiteHint: Likely ⟺ IP входа в белых (белые у операторов — по адресу), SNI не условие") {
        whiteHint(lists, "203.0.113.1") shouldBe WhiteHint.Likely
        whiteHint(lists, "9.9.9.9") shouldBe WhiteHint.Unlikely
    }
    test("sniWhite: отдельный факт; нет SNI — нет") {
        sniWhite(lists, "api.ya.ru") shouldBe true
        sniWhite(lists, "evil.com") shouldBe false
        sniWhite(lists, null) shouldBe false
    }
})
