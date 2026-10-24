package podbor.core

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.kotlincrypto.hash.sha2.SHA256

/**
 * Разбор ссылок подписки — не наш код: край превращает подписку в outbound'ы Xray готовым
 * конвертером обёртки Xray. Ядро читает только структурированный outbound — «как подключиться».
 */
data class Unusable(val reason: String)

/** Протоколы прокси, которые перебираем; freedom, blackhole и прочие служебные — не кандидаты. */
private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")

fun draftOf(name: String, outbound: JsonObject): Either<Unusable, ServerDraft> = either {
    val protocol = outbound.str("protocol")
    ensure(protocol.isNotEmpty()) { Unusable("нет protocol") }
    ensure(protocol in proxyProtocols) { Unusable("не прокси: $protocol") }
    val settings = outbound["settings"]
    ensure(settings is JsonObject) { Unusable("нет settings") }
    // vnext (vless/vmess), servers (trojan/shadowsocks) или плоская форма settings.
    val list = if (settings["vnext"] is JsonArray) settings.arr("vnext") else settings.arr("servers")
    val target = objOr(list.firstOrNull(), settings)
    val host = target.str("address")
    ensure(host.isNotEmpty()) { Unusable("нет address") }
    val portAt = target["port"]
    val port = ensureNotNull(if (portAt is JsonPrimitive) portAt.intOrNull else null) { Unusable("нет port") }
    ensure(port in 1..65535) { Unusable("нет port") }
    val stream = outbound.obj("streamSettings")
    val reality = stream.obj("realitySettings")
    val tls = stream.obj("tlsSettings")
    val flow = objOr(target.arr("users").firstOrNull(), EMPTY).str("flow").ifEmpty { target.str("flow") }
    ServerDraft(
        name = name,
        endpoint = Endpoint(host, port),
        transport = transportOf(stream.str("network"), stream.str("security"), flow),
        fingerprint = firstText(reality.str("fingerprint"), tls.str("fingerprint")),
        key = keyOf(outbound),
        sni = firstText(reality.str("serverName"), tls.str("serverName")),
    )
}

/** raw ≡ tcp, сети нет — tcp; RealityVision — только reality + flow vision, прочий tcp — Other("tcp"). */
private fun transportOf(network: String, security: String, flow: String): Transport =
    when (val n = network.lowercase().ifEmpty { "tcp" }) {
        "tcp", "raw" ->
            if (security == "reality" && flow == "xtls-rprx-vision") Transport.RealityVision else Transport.Other("tcp")
        "xhttp", "splithttp" -> Transport.Xhttp
        "grpc" -> Transport.Grpc
        "ws" -> Transport.Ws
        else -> Transport.Other(n)
    }

/** Хеш outbound'а без tag: ключ не зависит ни от имени, ни от позиции в подписке, секретов не раскрывает. */
private fun keyOf(outbound: JsonObject): String =
    SHA256().digest(JsonObject(outbound - "tag").toString().encodeToByteArray())
        .take(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/*
 * Чтение outbound'а: поля нет или оно другого вида — пустое значение (пустой объект, пустой список, пустая строка),
 * поэтому цепочки читаются без проверок на каждом шаге; обязательные поля проверяет [draftOf].
 */
private val EMPTY = JsonObject(emptyMap())

private fun objOr(e: JsonElement?, other: JsonObject): JsonObject = when (e) {
    is JsonObject -> e
    is JsonArray, is JsonPrimitive, null -> other
}

private fun JsonObject.obj(k: String): JsonObject = objOr(this[k], EMPTY)

private fun JsonObject.arr(k: String): List<JsonElement> = when (val v = this[k]) {
    is JsonArray -> v
    is JsonObject, is JsonPrimitive, null -> emptyList()
}

private fun JsonObject.str(k: String): String = when (val v = this[k]) {
    is JsonPrimitive -> if (v.isString) v.content else ""
    is JsonObject, is JsonArray, null -> ""
}

/** Первое непустое; нет — поля у сервера нет (в Ленте оно необязательное). */
private fun firstText(vararg xs: String): String? = xs.firstOrNull { it.isNotEmpty() }

/** Присвоить теги s001.. по порядку и вход из вшитой базы (её даёт край). */
fun classify(drafts: List<ServerDraft>, entryOf: (Endpoint) -> Entry): List<Server> =
    drafts.mapIndexed { i, d ->
        Server(
            id = ServerId("s" + (i + 1).toString().padStart(3, '0')),
            name = d.name,
            endpoint = d.endpoint,
            transport = d.transport,
            fingerprint = d.fingerprint,
            entry = entryOf(d.endpoint),
            key = d.key,
            sni = d.sni,
        )
    }
