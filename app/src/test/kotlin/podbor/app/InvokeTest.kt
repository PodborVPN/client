package podbor.app

import arrow.core.Either
import arrow.core.right
import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import podbor.app.xray.Invoke
import podbor.app.xray.LibXrayConverter
import podbor.app.xray.LibXrayEngine
import podbor.app.xray.parseConvertResponse
import podbor.app.xray.parseRunResponse
import podbor.app.xray.runRequest
import podbor.driver.EngineError

/** Ответы libXray — дословно из client/app/README.md (сняты в задаче 1). */
private const val RUN_OK = """{"success":true,"data":{},"error":""}"""
private const val CONVERT_OK =
    """{"success":true,"data":{"outbounds":[{"protocol":"vless","settings":{"address":"example.com","encryption":"none","id":"…","port":443},"streamSettings":{"network":"raw","security":"tls","tlsSettings":{"serverName":"example.com"}},"tag":"t"}]},"error":""}"""
private const val FAILED = """{"success":false,"data":null,"error":"xray is already running"}"""

/** Фейковый libXray: пишет вызванные методы, отвечает по таблице или бросает. */
private class FakeInvoke(private val answer: (method: String) -> String) : Invoke {
    val methods = mutableListOf<String>()
    val requests = mutableListOf<JsonObject>()
    override fun call(request: String): String {
        val json = Json.parseToJsonElement(request).jsonObject
        requests += json
        val method = json.getValue("method").jsonPrimitive.content
        methods += method
        return answer(method)
    }
}

private val config = buildJsonObject { put("log", buildJsonObject { put("loglevel", "warning") }) }

class InvokeTest : FunSpec({

    test("runRequest: конверт apiVersion 3, метод runXray, конфиг строкой в xrayJson") {
        val env = Json.parseToJsonElement(runRequest(config.toString())).jsonObject
        env.getValue("apiVersion").jsonPrimitive.int shouldBe 3
        env.getValue("method") shouldBe JsonPrimitive("runXray")
        env.getValue("payload").jsonObject.getValue("xrayJson") shouldBe JsonPrimitive(config.toString())
    }

    test("parseRunResponse: success → Right, отказ → Left с текстом ошибки, мусор → Left") {
        parseRunResponse(RUN_OK) shouldBe Unit.right()
        parseRunResponse(FAILED) shouldBe Either.Left(EngineError.Failed("libXray: xray is already running"))
        parseRunResponse("не json").shouldBeInstanceOf<Either.Left<EngineError>>()
    }

    test("parseConvertResponse: outbounds читаются; пустые, отказ и мусор → Left") {
        parseConvertResponse(CONVERT_OK).getOrNull()!! shouldHaveSize 1
        parseConvertResponse("""{"success":true,"data":{"outbounds":[]},"error":""}""").shouldBeInstanceOf<Either.Left<EngineError>>()
        parseConvertResponse(FAILED).shouldBeInstanceOf<Either.Left<EngineError>>()
        parseConvertResponse("").shouldBeInstanceOf<Either.Left<EngineError>>()
    }

    test("LibXrayEngine: run → runXray, выход из области → stopXray") {
        val fake = FakeInvoke { RUN_OK }
        val engine = LibXrayEngine(fake)
        resourceScope { with(engine) { run(config) } shouldBe Unit.right() }
        fake.methods shouldBe listOf("runXray", "stopXray")
        fake.requests.first().getValue("payload").jsonObject.getValue("xrayJson") shouldBe JsonPrimitive(config.toString())
    }

    test("LibXrayEngine: runXray отказал → Left, но stopXray всё равно вызван") {
        val fake = FakeInvoke { if (it == "runXray") FAILED else RUN_OK }
        resourceScope { with(LibXrayEngine(fake)) { run(config) }.shouldBeInstanceOf<Either.Left<EngineError>>() }
        fake.methods shouldBe listOf("runXray", "stopXray")
    }

    test("LibXrayEngine: исключение в области после запуска → stopXray вызван") {
        val fake = FakeInvoke { RUN_OK }
        shouldThrow<IllegalStateException> {
            resourceScope {
                with(LibXrayEngine(fake)) { run(config) }
                error("сбой в области")
            }
        }
        fake.methods shouldBe listOf("runXray", "stopXray")
    }

    test("LibXrayEngine: сам invoke бросил на runXray → Left, stopXray вызван") {
        val fake = FakeInvoke { if (it == "runXray") throw IllegalStateException("jni") else RUN_OK }
        resourceScope { with(LibXrayEngine(fake)) { run(config) }.shouldBeInstanceOf<Either.Left<EngineError>>() }
        fake.methods shouldBe listOf("runXray", "stopXray")
    }

    test("LibXrayEngine: stopXray отказал или бросил → предупреждение без текста ответа; успешная остановка — без предупреждений") {
        val warnings = mutableListOf<String>()
        resourceScope { with(LibXrayEngine(FakeInvoke { RUN_OK }) { warnings += it }) { run(config) } }
        val refusing = FakeInvoke { if (it == "stopXray") FAILED else RUN_OK }
        resourceScope { with(LibXrayEngine(refusing) { warnings += it }) { run(config) } }
        val throwing = FakeInvoke { if (it == "stopXray") throw IllegalStateException("jni") else RUN_OK }
        resourceScope { with(LibXrayEngine(throwing) { warnings += it }) { run(config) } }
        warnings shouldBe listOf("stopXray: success=false", "stopXray бросил IllegalStateException")
    }

    test("LibXrayConverter: convertShareLinksToXrayJson с текстом подписки в payload.text") {
        val fake = FakeInvoke { CONVERT_OK }
        LibXrayConverter(fake).convert("vless://x").getOrNull()!! shouldHaveSize 1
        fake.methods shouldBe listOf("convertShareLinksToXrayJson")
        fake.requests.single().getValue("payload").jsonObject.getValue("text") shouldBe JsonPrimitive("vless://x")
    }
})
