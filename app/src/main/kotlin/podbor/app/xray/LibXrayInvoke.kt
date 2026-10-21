package podbor.app.xray

import arrow.core.Either
import arrow.core.Nel
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import podbor.driver.EngineError
import podbor.driver.outboundsOfResponse

/**
 * Единственный вход в libXray: конверт запроса строкой → ответ строкой (client/app/README.md).
 * Прод — [LibXrayInvoke] (`libXray.LibXray.invoke`), тесты — фейк. Вызов блокирующий: звать с IO.
 */
fun interface Invoke {
    fun call(request: String): String
}

/** libXray из AAR: `public static native String invoke(String)`; Go-рантайм один на процесс. */
val LibXrayInvoke: Invoke = Invoke { libXray.LibXray.invoke(it) }

private fun envelope(method: String, payload: JsonObject? = null): String = buildJsonObject {
    put("apiVersion", 3)
    put("method", method)
    payload?.let { put("payload", it) }
}.toString()

/** runXray: конфиг Xray строкой в `xrayJson` (не путь к файлу). */
fun runRequest(configJson: String): String = envelope("runXray", buildJsonObject { put("xrayJson", configJson) })

fun stopRequest(): String = envelope("stopXray")

/** Подписка целиком в `text`; сам текст секретен — в логи не уходит ни запрос, ни ответ. */
fun convertRequest(text: String): String = envelope("convertShareLinksToXrayJson", buildJsonObject { put("text", text) })

/** Ответ `{"success":bool,"data":…,"error":"…"}` → data при успехе; отказ — Left с текстом libXray. */
private fun dataOf(json: String): Either<EngineError, JsonElement?> = either {
    val response = Either.catch { Json.parseToJsonElement(json).jsonObject }
        .mapLeft { EngineError.Failed("libXray вернул не JSON") }.bind()
    ensure(response["success"]?.jsonPrimitive?.booleanOrNull == true) {
        EngineError.Failed("libXray: ${response["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
    }
    response["data"]
}

/** Ответ без данных — runXray и stopXray. */
fun parseRunResponse(json: String): Either<EngineError, Unit> = dataOf(json).map { }

/** Тот же разбор, что у JVM-конвертера ([podbor.driver.LibXrayCli]): пустые outbounds — Left. */
fun parseConvertResponse(json: String): Either<EngineError, Nel<JsonObject>> = outboundsOfResponse(json)
