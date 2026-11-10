package podbor.driver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private const val API_VERSION = 3

/** Конверт Invoke libXray: неизвестные поля ответа не мешают, `null` там — как отсутствующее поле. */
internal val libXrayJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

@Serializable
internal data class RunEnvelope(val method: String, val payload: RunPayload, val apiVersion: Int = API_VERSION)

@Serializable
internal data class RunPayload(val xrayJson: String)

@Serializable
internal data class BareEnvelope(val method: String, val apiVersion: Int = API_VERSION)

@Serializable
internal data class ConvertEnvelope(val method: String, val payload: ConvertPayload, val apiVersion: Int = API_VERSION)

@Serializable
internal data class ConvertPayload(val text: String)

/** Ответ без данных: runXray и stopXray. */
@Serializable
internal data class RunReply(val success: Boolean = false, val error: String = "")

/** Ответ конвертера; outbound — чужой JSON, его поля разбирает [podbor.core.draftOf]. */
@Serializable
internal data class ConvertReply(val success: Boolean = false, val data: ConvertData = ConvertData(), val error: String = "")

@Serializable
internal data class ConvertData(val outbounds: List<JsonObject> = emptyList())

/** runXray: конфиг Xray строкой в `xrayJson` (не путь к файлу). */
fun runRequest(configJson: String): String =
    libXrayJson.encodeToString(RunEnvelope.serializer(), RunEnvelope("runXray", RunPayload(configJson)))

fun stopRequest(): String = libXrayJson.encodeToString(BareEnvelope.serializer(), BareEnvelope("stopXray"))

/** Подписка целиком в `text`; сам текст секретен — в логи не уходит ни запрос, ни ответ. */
fun convertRequest(text: String): String = libXrayJson.encodeToString(
    ConvertEnvelope.serializer(),
    ConvertEnvelope("convertShareLinksToXrayJson", ConvertPayload(text)),
)
