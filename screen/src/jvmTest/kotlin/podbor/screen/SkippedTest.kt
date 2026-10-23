package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.encoding.Base64

/** Что из подписки не стало серверами: счёт ссылок по схемам против числа серверов конвертера. */
class SkippedTest : FunSpec({

    fun sub(vararg lines: String) = lines.joinToString("\n")

    test("все ссылки наши и все стали серверами — пропусков нет") {
        val s = skippedOf(sub("vless://a@h:1", "vmess://e30", "trojan://p@h:1", "ss://e30@h:1"), servers = 4)
        s shouldBe Skipped(servers = 4, unsupported = emptyMap(), broken = 0)
        s.any shouldBe false
        s.recognized shouldBe true
    }

    test("чужие схемы — по счёту каждой; наши стали серверами") {
        val text = sub("hysteria2://p@h:1", "tuic://a@h:1", "vless://a@h:1", "wireguard://k", "tuic://b@h:1", "socks://a@h:1")
        skippedOf(text, servers = 1) shouldBe
            Skipped(servers = 1, unsupported = mapOf("hysteria2" to 1, "tuic" to 2, "wireguard" to 1, "socks" to 1), broken = 0)
    }

    test("hy2 — тот же hysteria2; схема без учёта регистра") {
        skippedOf(sub("hy2://p@h:1", "Hysteria2://p", "VLESS://a@h:1"), servers = 1) shouldBe
            Skipped(servers = 1, unsupported = mapOf("hysteria2" to 2), broken = 0)
    }

    test("наша схема, а сервером не стала — битая") {
        val s = skippedOf(sub("vless://a@h", "vless://a@h:1", "trojan://@h:1"), servers = 1)
        s shouldBe Skipped(servers = 1, unsupported = emptyMap(), broken = 2)
        s.any shouldBe true
    }

    test("ничего не подошло: только чужие схемы — формат узнан, серверов нет") {
        val s = skippedOf(sub("anytls://p@h:1", "ssr://abc"), servers = 0)
        s shouldBe Skipped(servers = 0, unsupported = mapOf("anytls" to 1, "ssr" to 1), broken = 0)
        s.recognized shouldBe true
    }

    test("ни одной ссылки (Clash YAML, JSON) — формат не узнан") {
        val clash = sub("proxies:", "  - name: a", "    type: vless", "    server: h", "    port: 443")
        val s = skippedOf(clash, servers = 0)
        s shouldBe Skipped(servers = 0, unsupported = emptyMap(), broken = 0)
        s.recognized shouldBe false
        skippedOf("""{"outbounds":[{"protocol":"vless"}]}""", servers = 0).recognized shouldBe false
    }

    test("подписка в base64 (с отступами и без, url-safe, с переносами) считается как открытая") {
        val plain = sub("vless://a@h:1", "hysteria2://p@h:1", "tuic://a@h:1")
        val expected = skippedOf(plain, servers = 1)
        val bytes = plain.encodeToByteArray()
        skippedOf(Base64.encode(bytes), servers = 1) shouldBe expected
        skippedOf(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes), servers = 1) shouldBe expected
        skippedOf(Base64.encode(bytes).chunked(16).joinToString("\n") + "\n", servers = 1) shouldBe expected
    }

    test("пустые строки, комментарии и ремарки провайдера не считаются") {
        skippedOf(sub("# подписка", "", "  vless://a@h:1  ", "сервер Германия", ""), servers = 1) shouldBe
            Skipped(servers = 1, unsupported = emptyMap(), broken = 0)
    }

    test("серверов больше наших ссылок — битых не бывает меньше нуля") {
        skippedOf("vless://a@h:1", servers = 2).broken shouldBe 0
    }
})
