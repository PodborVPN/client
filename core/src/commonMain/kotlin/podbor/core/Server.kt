package podbor.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Тег сервера в нажатии: s001, s002… по порядку в подписке. На Ленте — только он, без адреса. */
@Serializable
@JvmInline
value class ServerId(val tag: String)

/** IP:порт сервера. host — уже разрешённый краем адрес: бюджет и блоки считаются по нему. */
@Serializable
data class Endpoint(val host: String, val port: Int)

/**
 * Где вход сервера — по тому же geoip.dat, что у маршрутизации Xray (geoip:ru), без сети.
 * ASN в geoip.dat нет и не нужен: блок ставится на IP:порт, лимиты — по нему.
 */
@Serializable
sealed interface Entry {
    @Serializable data object Ru : Entry
    @Serializable data class Foreign(val country: String) : Entry
    @Serializable data object Unknown : Entry
}

@Serializable
sealed interface Transport {
    @Serializable data object RealityVision : Transport
    @Serializable data object Xhttp : Transport
    @Serializable data object Grpc : Transport
    @Serializable data object Ws : Transport
    @Serializable data class Other(val name: String) : Transport
}

/**
 * Сервер из outbound'а Xray до классификации входа.
 * name — подпись провайдера для экрана, логика её не читает; key — устойчивый хеш outbound'а.
 */
@Serializable
data class ServerDraft(
    val name: String,
    val endpoint: Endpoint,
    val transport: Transport,
    val fingerprint: String?,
    val key: String,
    /** SNI из reality/tls-настроек: по нему подсказка белых списков; null — без TLS или не задан. */
    val sni: String? = null,
)

@Serializable
data class Server(
    val id: ServerId,
    val name: String,
    val endpoint: Endpoint,
    val transport: Transport,
    val fingerprint: String?,
    val entry: Entry,
    /** Устойчивый ключ (хеш outbound'а): память сети переживает перестановку подписки. */
    val key: String,
    val sni: String? = null,
)
