package podbor.core

import arrow.core.Either.Left
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Outbound Xray (как его отдаёт конвертер ссылок края) → черновик сервера. Строки ссылок ядро не читает. */
class OutboundTest : FunSpec({

    fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    fun ok(name: String, s: String): ServerDraft = draftOf(name, json(s)).getOrElse { error("непригоден: $it") }

    test("vless reality tcp + vision → RealityVision, fp из realitySettings") {
        val d = ok(
            "X",
            """{"protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":443,"users":[{"id":"u","flow":"xtls-rprx-vision"}]}]},
               "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"fingerprint":"chrome","serverName":"a.b"}}}""",
        )
        d.endpoint shouldBe Endpoint("1.2.3.4", 443)
        d.transport shouldBe Transport.RealityVision
        d.fingerprint shouldBe "chrome"
        d.name shouldBe "X"
    }

    test("network xhttp / grpc / ws / raw без vision") {
        fun t(net: String) = ok("a", """{"protocol":"vless","settings":{"vnext":[{"address":"h","port":443,"users":[{"id":"u"}]}]},
            "streamSettings":{"network":"$net","security":"tls","tlsSettings":{"fingerprint":"firefox"}}}""").transport
        t("xhttp") shouldBe Transport.Xhttp
        t("grpc") shouldBe Transport.Grpc
        t("ws") shouldBe Transport.Ws
        t("raw") shouldBe Transport.Other("tcp")
    }

    test("trojan и shadowsocks — settings.servers; без streamSettings → tcp") {
        ok("T", """{"protocol":"trojan","settings":{"servers":[{"address":"5.6.7.8","port":443,"password":"p"}]},"streamSettings":{"network":"ws"}}""")
            .endpoint shouldBe Endpoint("5.6.7.8", 443)
        ok("S", """{"protocol":"shadowsocks","settings":{"servers":[{"address":"9.9.9.9","port":8388,"method":"aes-256-gcm","password":"p"}]}}""")
            .transport shouldBe Transport.Other("tcp")
    }

    test("плоская форма settings (address/port без vnext) тоже принимается") {
        ok("F", """{"protocol":"vless","settings":{"address":"203.0.113.7","port":2096,"id":"u"},"streamSettings":{"network":"grpc"}}""")
            .endpoint shouldBe Endpoint("203.0.113.7", 2096)
    }

    test("имя ни на что не влияет: стрелки и эмодзи — просто подпись") {
        val body = """{"protocol":"vless","settings":{"vnext":[{"address":"h","port":443,"users":[{"id":"u"}]}]},"streamSettings":{"network":"grpc"}}"""
        ok("🇷🇺 RU-2 -> 🇳🇱 NL-7", body).copy(name = "") shouldBe ok("что угодно", body).copy(name = "")
    }

    test("freedom, нет адреса, порт-массив → Left(Unusable), без исключений") {
        draftOf("x", json("""{"protocol":"freedom"}""")) shouldBe Left(Unusable("не прокси: freedom"))
        draftOf("x", json("""{"protocol":"vless","settings":{"vnext":[{"port":443}]}}""")) shouldBe Left(Unusable("нет address"))
        draftOf("x", json("""{"protocol":"vless","settings":{"vnext":[{"address":"h","port":[1]}]}}""")) shouldBe Left(Unusable("нет port"))
    }

    test("ключ устойчив к имени и различает разные outbound'ы") {
        val a = """{"protocol":"vless","settings":{"vnext":[{"address":"h","port":443,"users":[{"id":"u"}]}]}}"""
        val b = """{"protocol":"vless","settings":{"vnext":[{"address":"h","port":8443,"users":[{"id":"u"}]}]}}"""
        ok("a", a).key shouldBe ok("другое имя", a).key
        ok("a", a).key shouldNotBe ok("a", b).key
    }

    test("classify нумерует s001.. по порядку и берёт entry из entryOf") {
        val drafts = listOf(
            ok("a", """{"protocol":"vless","settings":{"address":"1.1.1.1","port":443}}"""),
            ok("b", """{"protocol":"vless","settings":{"address":"198.51.100.2","port":443}}"""),
        )
        val servers = classify(drafts) { e -> if (e.host == "1.1.1.1") Entry.Ru else Entry.Foreign("NL") }
        servers.map { it.id } shouldBe listOf(ServerId("s001"), ServerId("s002"))
        servers.map { it.entry } shouldBe listOf(Entry.Ru, Entry.Foreign("NL"))
    }

    test("sni: reality.serverName, tls.serverName, без TLS — null") {
        fun sni(stream: String) = ok("a", """{"protocol":"vless","settings":{"vnext":[{"address":"h","port":443,"users":[{"id":"u"}]}]}$stream}""").sni
        sni(""","streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"r.example"}}""") shouldBe "r.example"
        sni(""","streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"t.example"}}""") shouldBe "t.example"
        sni(""","streamSettings":{"network":"tcp","security":"none"}""") shouldBe null
        sni("") shouldBe null
    }
})
