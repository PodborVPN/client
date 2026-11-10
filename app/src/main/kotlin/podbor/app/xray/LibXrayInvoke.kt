package podbor.app.xray

import arrow.core.Either
import arrow.core.Nel
import kotlinx.serialization.json.JsonObject
import podbor.driver.EngineError
import podbor.driver.convertRequest as convertEnvelope
import podbor.driver.outboundsOfResponse
import podbor.driver.runReplyOf
import podbor.driver.runRequest as runEnvelope
import podbor.driver.stopRequest as stopEnvelope

/**
 * Единственный вход в libXray: конверт запроса строкой → ответ строкой (client/app/README.md).
 * Прод — [LibXrayInvoke] (`libXray.LibXray.invoke`), тесты — фейк. Вызов блокирующий: звать с IO.
 */
fun interface Invoke {
    fun call(request: String): String
}

/** libXray из AAR: `public static native String invoke(String)`; Go-рантайм один на процесс. */
val LibXrayInvoke: Invoke = Invoke { libXray.LibXray.invoke(it) }

/** runXray: конфиг Xray строкой в `xrayJson` (не путь к файлу). */
fun runRequest(configJson: String): String = runEnvelope(configJson)

fun stopRequest(): String = stopEnvelope()

/** Подписка целиком в `text`; сам текст секретен — в логи не уходит ни запрос, ни ответ. */
fun convertRequest(text: String): String = convertEnvelope(text)

/** Ответ без данных — runXray и stopXray. */
fun parseRunResponse(json: String): Either<EngineError, Unit> = runReplyOf(json)

/** Тот же разбор, что у JVM-конвертера ([podbor.driver.LibXrayCli]): пустые outbounds — Left. */
fun parseConvertResponse(json: String): Either<EngineError, Nel<JsonObject>> = outboundsOfResponse(json)
