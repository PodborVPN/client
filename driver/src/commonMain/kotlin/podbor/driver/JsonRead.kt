package podbor.driver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Чтение чужого JSON (ответы Xray, строки Ленты): поля нет или оно другого вида — пустое значение (пустой объект,
 * пустой список, пустая строка, false), поэтому цепочки читаются без проверок на каждом шаге.
 */

internal val EMPTY_JSON = JsonObject(emptyMap())

internal fun JsonObject.obj(k: String): JsonObject = when (val v = this[k]) {
    is JsonObject -> v
    is JsonArray, is JsonPrimitive, null -> EMPTY_JSON
}

internal fun JsonObject.arr(k: String): List<JsonElement> = when (val v = this[k]) {
    is JsonArray -> v
    is JsonObject, is JsonPrimitive, null -> emptyList()
}

/** Значение примитива строкой (`"x"`, `1`, `true`); null JSON, объект, список, нет поля — пусто. */
internal fun JsonObject.text(k: String): String = when (val v = this[k]) {
    JsonNull -> ""
    is JsonPrimitive -> v.content
    is JsonObject, is JsonArray, null -> ""
}

/** Поле — логическое `true`; иначе (нет, строка "true", другой вид) — false. */
internal fun JsonObject.flag(k: String): Boolean = when (val v = this[k]) {
    is JsonPrimitive -> !v.isString && v.content == "true"
    is JsonObject, is JsonArray, null -> false
}
