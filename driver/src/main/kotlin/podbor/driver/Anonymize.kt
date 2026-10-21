package podbor.driver

import arrow.core.Either
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Лента к отдаче наружу: без того, что выдаёт подписку и человека, но так, что по ней по-прежнему разбираются
 * беды и прогон (replay) идёт как по исходной. Псевдонимы — на всю выгрузку: один адрес (ключ, SNI, имя) — один
 * псевдоним во всех строках и прогонах, поэтому равенства, на которых держится подбор (адрес блока = адрес сервера,
 * ключ памяти = ключ сервера), сохраняются. Строка, которую не разобрать (оборванная последняя), выбрасывается: что в
 * ней — неизвестно.
 *
 * Заменяются: адреса (`host`, `ip`) и любой IPv4 в любой строке (ноты с текстом ошибок), ключи серверов (`key`,
 * `serverKey`, ключи словарей и значения списков вида 16 hex), SNI, имена серверов из подписки; у вышки — `cellId` (место человека).
 * Остаются: оператор, сценарий, протокол, порт, вход РФ/зарубеж и страна, подсказки белых, вердикты, времена, эталоны
 * линии (свои и публичные), диапазон и сигнал вышки.
 */
fun anonymize(tape: String): String {
    val a = Aliases()
    return tape.lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull { line -> Either.catch { Json.parseToJsonElement(line) }.getOrNull()?.let { a.walk(it).toString() } }
        .joinToString("") { it + "\n" }
}

private val HEX_KEY = Regex("[0-9a-f]{16}")
private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
private const val TOWER_KNOWN = "podbor.core.Tower.Known"

/** Таблицы псевдонимов одной выгрузки, по виду значения. */
private class Aliases {
    private val hosts = HashMap<String, String>()
    private val keys = HashMap<String, String>()
    private val snis = HashMap<String, String>()
    private val names = HashMap<String, String>()

    private fun host(v: String) = hosts.getOrPut(v) { "h${hosts.size + 1}" }
    private fun key(v: String) = keys.getOrPut(v) { "k%015d".format(keys.size + 1) }
    private fun sni(v: String) = snis.getOrPut(v) { "sni${snis.size + 1}" }
    private fun name(v: String) = names.getOrPut(v) { "Сервер №${names.size + 1}" }

    /** IPv4 в свободном тексте — тем же псевдонимом, что адрес в своём поле. */
    private fun scrub(s: String): String = IPV4.replace(s) { host(it.value) }

    fun walk(e: JsonElement): JsonElement = when (e) {
        is JsonArray -> JsonArray(e.map(::walk))
        // Ключ сервера — и значением в списке (`sniWhite` анализа — набор ключей), не только полем.
        is JsonPrimitive -> when {
            !e.isString -> e
            HEX_KEY.matches(e.content) -> JsonPrimitive(key(e.content))
            else -> JsonPrimitive(scrub(e.content))
        }
        is JsonObject -> obj(e)
    }

    private fun obj(o: JsonObject): JsonObject {
        val server = "endpoint" in o && "key" in o
        val tower = (o["type"] as? JsonPrimitive)?.contentOrNull == TOWER_KNOWN
        return JsonObject(
            o.entries.associate { (k, v) ->
                val name = if (HEX_KEY.matches(k)) key(k) else k
                name to when {
                    tower && k == "cellId" -> JsonPrimitive(0)
                    v !is JsonPrimitive || !v.isString -> walk(v)
                    k == "host" || k == "ip" -> JsonPrimitive(host(v.content))
                    k == "key" || k == "serverKey" -> JsonPrimitive(key(v.content))
                    k == "sni" -> JsonPrimitive(sni(v.content))
                    k == "name" && server -> JsonPrimitive(name(v.content))
                    else -> walk(v)
                }
            },
        )
    }
}
