package podbor.core

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class HysteriaImportTest : FunSpec({
    fun outbound(version: Int = 2, network: String = "hysteria", security: String = "tls") =
        Json.parseToJsonElement("""{"protocol":"hysteria","settings":{"version":$version,"address":"203.0.113.7","port":443},"streamSettings":{"network":"$network","security":"$security","tlsSettings":{"serverName":"example.org"},"hysteriaSettings":{"version":2,"auth":"test-only"}}}""").jsonObject

    test("Hysteria 2 становится кандидатом с адресом и именем TLS") {
        val result = draftOf("UDP", outbound()).shouldBeInstanceOf<Either.Right<ServerDraft>>().value
        result.endpoint shouldBe Endpoint("203.0.113.7", 443)
        result.sni shouldBe "example.org"
        transportWords(result.transport) shouldBe "Hysteria 2"
    }

    test("неподдержанная версия или транспорт не становятся кандидатом Hysteria 2") {
        draftOf("UDP", outbound(version = 1)).isLeft() shouldBe true
        draftOf("UDP", outbound(network = "tcp")).isLeft() shouldBe true
        draftOf("UDP", outbound(security = "none")).isLeft() shouldBe true
    }
})
