package podbor.core

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest

/**
 * Разбор ссылок подписки — не наш код: край превращает подписку в outbound'ы Xray готовым
 * конвертером обёртки Xray. Ядро читает только структурированный outbound — «как подключиться».
 */
data class Unusable(val reason: String)

/** Протоколы прокси, которые перебираем; freedom, blackhole и прочие служебные — не кандидаты. */
private val proxyProtocols = setOf("vless", "vmess", "trojan", "shadowsocks")

fun draftOf(name: String, outbound: JsonObject): Either<Unusable, ServerDraft> = either {
    val protocol = ensureNotNull(outbound.str("protocol")) { Unusable("нет protocol") }
    ensure(protocol in proxyProtocols) { Unusable("не прокси: $protocol") }
    val settings = ensureNotNull(outbound.obj("settings")) { Unusable("нет settings") }
    // vnext (vless/vmess), servers (trojan/shadowsocks) или плоская форма settings.
    val target = (settings["vnext"] as? JsonArray ?: settings["servers"] as? JsonArray)
        ?.firstOrNull() as? JsonObject ?: settings
    val host = ensureNotNull(target.str("address")) { Unusable("нет address") }
    val port = ensureNotNull((target["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 }) { Unusable("нет port") }
    val stream = outbound.obj("streamSettings")
    val flow = ((target["users"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("flow") ?: target.str("flow")
    ServerDraft(
        name = name,
        endpoint = Endpoint(host, port),
        transport = transportOf(stream?.str("network"), stream?.str("security"), flow),
        fingerprint = stream?.obj("realitySettings")?.str("fingerprint") ?: stream?.obj("tlsSettings")?.str("fingerprint"),
        key = keyOf(outbound),
        sni = stream?.obj("realitySettings")?.str("serverName") ?: stream?.obj("tlsSettings")?.str("serverName"),
    )
}

/** raw ≡ tcp; RealityVision — только reality + flow vision, прочий tcp — Other("tcp"). */
private fun transportOf(network: String?, security: String?, flow: String?): Transport =
    when (val n = network?.lowercase() ?: "tcp") {
        "tcp", "raw" ->
            if (security == "reality" && flow == "xtls-rprx-vision") Transport.RealityVision else Transport.Other("tcp")
        "xhttp", "splithttp" -> Transport.Xhttp
        "grpc" -> Transport.Grpc
        "ws" -> Transport.Ws
        else -> Transport.Other(n)
    }

/** Хеш outbound'а без tag: ключ не зависит ни от имени, ни от позиции в подписке, секретов не раскрывает. */
private fun keyOf(outbound: JsonObject): String =
    MessageDigest.getInstance("SHA-256").digest(JsonObject(outbound - "tag").toString().toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
private fun JsonObject.str(k: String): String? =
    (this[k] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotEmpty)

/** Присвоить теги s001.. по порядку и вход из вшитой базы (её даёт край). */
fun classify(drafts: List<ServerDraft>, entryOf: (Endpoint) -> Entry): List<Server> =
    drafts.mapIndexed { i, d ->
        Server(
            id = ServerId("s%03d".format(i + 1)),
            name = d.name,
            endpoint = d.endpoint,
            transport = d.transport,
            fingerprint = d.fingerprint,
            entry = entryOf(d.endpoint),
            key = d.key,
            sni = d.sni,
        )
    }
