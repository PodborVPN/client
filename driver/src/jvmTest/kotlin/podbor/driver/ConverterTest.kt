package podbor.driver

import arrow.core.Either.Left
import arrow.core.getOrElse
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import podbor.core.Entry
import podbor.core.ServerId
import podbor.core.Transport
import podbor.core.classify

class ConverterTest : FunSpec({

    val sample = javaClass.getResource("/converter-sample.txt")!!.readText()
    val outbounds = outboundsOfResponse(sample).getOrElse { error("фикстура не разобралась: $it") }

    test("ответ Invoke: предупреждения Xray перед JSON отбрасываются, outbound'ы читаются") {
        outbounds shouldHaveSize 6
    }

    test("outbounds пуст → Left(Failed(\"конвертер: нет outbounds\"))") {
        outboundsOfResponse("""{"success":true,"data":{"outbounds":[]}}""") shouldBe Left(EngineError.Failed("конвертер: нет outbounds"))
    }

    test("ответ с success=false → Left") {
        outboundsOfResponse("""{"success":false,"data":null,"error":"unsupported share format"}""").isLeft() shouldBe true
        outboundsOfResponse("").isLeft() shouldBe true
    }

    test("имя = исходный tag конвертера, транспорт — из streamSettings") {
        val drafts = draftsOf(outbounds)
        drafts.map { it.name }[4] shouldBe "🇷🇺 RU-1 -> 🇳🇱 NL-3(gRPC)"
        drafts.map { it.transport }.take(3) shouldBe listOf(Transport.RealityVision, Transport.Xhttp, Transport.Grpc)
    }

    test("две одинаковые ремарки → два кандидата s001, s002") {
        fun ob(port: Int) = buildJsonObject {
            put("protocol", "vless"); put("tag", "Germany")
            putJsonObject("settings") { put("address", "10.0.0.1"); put("port", port) }
        }
        val servers = classify(draftsOf(listOf(ob(443), ob(8443)))) { Entry.Unknown }
        servers.map { it.id } shouldBe listOf(ServerId("s001"), ServerId("s002"))
        servers.map { it.name } shouldBe listOf("Germany", "Germany")
    }

    test("тот же конфиг под двумя ремарками — один кандидат (первый): ключ сервера один, Xray у них один outbound") {
        fun ob(tag: String) = buildJsonObject {
            put("protocol", "vless"); put("tag", tag)
            putJsonObject("settings") { put("address", "10.0.0.1"); put("port", 443) }
        }
        val drafts = draftsOf(listOf(ob("Germany"), ob("Germany 2"), ob("Germany")))
        drafts.map { it.name } shouldBe listOf("Germany")
        classify(drafts) { Entry.Unknown }.map { it.key }.toSet().size shouldBe 1
    }

    test("retag: outbound получает tag = ServerId, остальное без изменений") {
        val servers = classify(draftsOf(outbounds)) { Entry.Unknown }
        val byId = retag(servers, outbounds)
        byId.keys shouldBe servers.map { it.id }.toSet()
        byId.getValue(ServerId("s005"))["tag"] shouldBe JsonPrimitive("s005")
        (byId.getValue(ServerId("s005")) - "tag") shouldBe (outbounds[4] - "tag")
    }

    test("непригодный outbound (freedom) не становится кандидатом") {
        draftsOf(listOf(buildJsonObject { put("protocol", "freedom"); put("tag", "direct") })).shouldBeInstanceOf<List<*>>().size shouldBe 0
    }
})
