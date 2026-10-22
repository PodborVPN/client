package podbor.driver

import arrow.core.Either
import arrow.core.Nel
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.toNonEmptyListOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import podbor.core.Server
import podbor.core.ServerDraft
import podbor.core.ServerId
import podbor.core.draftOf

/**
 * Ответ Invoke(convertShareLinksToXrayJson). Xray пишет предупреждения в тот же поток,
 * поэтому ответ — последняя непустая строка.
 */
fun outboundsOfResponse(stdout: String): Either<EngineError, Nel<JsonObject>> = either {
    val last = ensureNotNull(stdout.lineSequence().lastOrNull { it.isNotBlank() }) { EngineError.Failed("конвертер ничего не вернул") }
    val response = Either.catch { Json.parseToJsonElement(last).jsonObject }
        .mapLeft { EngineError.Failed("конвертер вернул не JSON") }.bind()
    ensure(response["success"]?.jsonPrimitive?.booleanOrNull == true) {
        EngineError.Failed("конвертер: ${response["error"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
    }
    val list = ensureNotNull((response["data"] as? JsonObject)?.get("outbounds") as? JsonArray) { EngineError.Failed("конвертер: нет outbounds") }
    ensureNotNull(list.mapNotNull { it as? JsonObject }.toNonEmptyListOrNull()) { EngineError.Failed("конвертер: нет outbounds") }
}

/** Имя кандидата = исходный tag (ремарка провайдера) — только подпись; непригодные отбрасываются. */
fun draftsOf(outbounds: List<JsonObject>): List<ServerDraft> =
    outbounds.mapNotNull { ob ->
        val name = (ob["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        draftOf(name, ob).getOrNull()
    }

/** Outbound каждого кандидата под тегом ServerId; связь — по устойчивому ключу (хеш outbound'а без tag). */
fun retag(servers: List<Server>, outbounds: List<JsonObject>): Map<ServerId, JsonObject> {
    val byKey = outbounds.mapNotNull { ob -> draftOf("", ob).getOrNull()?.let { it.key to ob } }.toMap()
    return servers.associate { s -> s.id to JsonObject(byKey.getValue(s.key) + ("tag" to JsonPrimitive(s.id.tag))) }
}
