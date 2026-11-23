package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import podbor.core.*

class HysteriaConfigTest : FunSpec({
    val outbound = Json.parseToJsonElement("""{"tag":"UDP","protocol":"hysteria","settings":{"address":"203.0.113.7","port":443,"version":2},"streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":"example.org"},"hysteriaSettings":{"auth":"test-only","version":2},"finalmask":{"udp":[{"type":"salamander","settings":{"password":"test-only"}}]}}}""").jsonObject

    test("конфиг анализа и сессии сохраняет параметры Hysteria и маскировку во всех выходах") {
        val servers = classify(draftsOf(listOf(outbound))) { Entry.Unknown }
        servers.size shouldBe 1
        servers.single().transport shouldBe Transport.Hysteria2
        val tagged = retag(servers, listOf(outbound))
        listOf(Tun.Off, Tun.Proxy("127.0.0.1", 8898), Tun.On(42)).forEach { tun ->
            val config = buildConfig(servers, tagged, ConfigOpts(assetDir = "/assets", tun = tun, services = setOf(Telegram, Instagram, Web)))
            val proxies = config.getValue("outbounds").jsonArray.map { it.jsonObject }.filter { it["protocol"] == JsonPrimitive("hysteria") }
            proxies.isNotEmpty() shouldBe true
            proxies.forEach { proxy ->
                proxy["settings"] shouldBe outbound["settings"]
                proxy["streamSettings"] shouldBe outbound["streamSettings"]
            }
        }
    }
})
