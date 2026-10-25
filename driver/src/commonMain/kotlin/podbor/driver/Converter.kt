package podbor.driver

import arrow.core.Either
import arrow.core.Nel
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.toNonEmptyListOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
    ensure(response.flag("success")) { EngineError.Failed("конвертер: ${response.text("error")}") }
    val list = response.obj("data").arr("outbounds").filterIsInstance<JsonObject>()
    ensureNotNull(list.toNonEmptyListOrNull()) { EngineError.Failed("конвертер: нет outbounds") }
}

/**
 * Имя кандидата = исходный tag (ремарка провайдера) — только подпись; непригодные отбрасываются. Тот же конфиг под
 * другой ремаркой — тот же сервер (ключ один, outbound у Xray один): кандидат — первый по порядку.
 */
fun draftsOf(outbounds: List<JsonObject>): List<ServerDraft> = usable(outbounds).distinctBy { it.key }

/** Ключи пригодных outbound'ов по порядку, с повторами: сколько ссылок стало сервером, считая одинаковые. */
fun draftKeysOf(outbounds: List<JsonObject>): List<String> = usable(outbounds).map { it.key }

private fun usable(outbounds: List<JsonObject>): List<ServerDraft> = outbounds.mapNotNull { ob -> draftOf(ob.text("tag"), ob).getOrNull() }

/** Outbound каждого кандидата под тегом ServerId; связь — по устойчивому ключу (хеш outbound'а без tag). */
fun retag(servers: List<Server>, outbounds: List<JsonObject>): Map<ServerId, JsonObject> {
    val byKey = outbounds.mapNotNull { ob -> draftOf("", ob).map { it.key to ob }.getOrNull() }.toMap()
    return servers.associate { s -> s.id to JsonObject(byKey.getValue(s.key) + ("tag" to JsonPrimitive(s.id.tag))) }
}
