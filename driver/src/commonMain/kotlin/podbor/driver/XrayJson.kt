@file:OptIn(ExperimentalSerializationApi::class)

package podbor.driver

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject

/*
 * Конфиг Xray типами: ключ — имя поля (@SerialName — под JSON Xray), опечатку ловит компилятор, а не Xray на старте.
 * Только то, что Podbor пишет сам; выходы серверов — JsonObject конвертера Xray (их форма — у Xray, не у нас).
 * Поле `T? = null` — ключа в JSON нет; решает, есть ли он, сборка конфига из алгебры опций, не умолчание.
 */

/** JSON конфига: умолчания пишутся (постоянные поля Xray), null — нет ключа. */
internal val xrayJson = Json { encodeDefaults = true; explicitNulls = false }

@Serializable
internal data class XrayConf(
    val log: LogConf,
    val env: Map<String, String>,
    val api: ApiConf,
    val stats: StatsConf,
    val policy: PolicyConf,
    val dns: DnsConf,
    val inbounds: List<InboundConf>,
    val outbounds: List<JsonObject>,
    val routing: RoutingConf,
)

@Serializable
internal data class LogConf(val loglevel: String, val access: String? = null)

@Serializable
internal data class ApiConf(val tag: String, val services: List<String>)

/** Счётчики Xray: секция без полей включает их. */
@Serializable
internal data object StatsConf

@Serializable
internal data class PolicyConf(val system: SystemPolicy)

@Serializable
internal data class SystemPolicy(
    val statsInboundUplink: Boolean,
    val statsInboundDownlink: Boolean,
    val statsOutboundUplink: Boolean,
    val statsOutboundDownlink: Boolean,
)

@Serializable
internal data class DnsConf(val servers: List<DnsServer>)

/** Сервер DNS Xray; [domains] — только для этих доменов, null — для всех остальных. */
@Serializable
internal data class DnsServer(val address: String, val domains: List<String>? = null)

/** Вход Xray: вид — поле `protocol`, как в JSON Xray. */
@Serializable
@JsonClassDiscriminator("protocol")
internal sealed interface InboundConf {
    val tag: String

    @Serializable
    @SerialName("dokodemo-door")
    data class Api(override val tag: String, val listen: String, val port: Int? = null, val settings: DokodemoSettings) : InboundConf

    @Serializable
    @SerialName("socks")
    data class Socks(override val tag: String, val listen: String, val port: Int, val settings: SocksSettings) : InboundConf

    @Serializable
    @SerialName("tun")
    data class Tun(override val tag: String, val settings: TunSettings, val sniffing: Sniffing) : InboundConf
}

@Serializable
internal data class DokodemoSettings(val address: String)

/** SOCKS-вход: [auth] и [accounts] — оба или ни одного (вход с паролем или открытый). */
@Serializable
internal data class SocksSettings(val udp: Boolean, val auth: String? = null, val accounts: List<SocksAccount>? = null)

@Serializable
internal data class SocksAccount(val user: String, val pass: String)

@Serializable
internal data class TunSettings(val name: String, val mtu: Int)

@Serializable
internal data class Sniffing(val enabled: Boolean, val destOverride: List<String>, val routeOnly: Boolean)

/** Служебный выход Xray; выходы серверов — JsonObject конвертера. */
@Serializable
@JsonClassDiscriminator("protocol")
internal sealed interface BuiltinOutbound {
    val tag: String

    @Serializable @SerialName("freedom") data class Freedom(override val tag: String) : BuiltinOutbound
    @Serializable @SerialName("blackhole") data class Blackhole(override val tag: String) : BuiltinOutbound
    @Serializable @SerialName("dns") data class Dns(override val tag: String) : BuiltinOutbound
}

@Serializable
internal data class RoutingConf(
    val domainStrategy: String,
    val rules: List<RuleConf>,
    val balancers: List<BalancerConf>? = null,
)

/** Правило маршрутизации в JSON Xray; строится [rule] — ровно с одной целью. */
@Serializable
internal data class RuleConf(
    val ruleTag: String? = null,
    val inboundTag: List<String>? = null,
    val port: Int? = null,
    val network: String? = null,
    val domain: List<String>? = null,
    val ip: List<String>? = null,
    val process: List<String>? = null,
    val outboundTag: String? = null,
    val balancerTag: String? = null,
)

@Serializable
internal data class BalancerConf(val tag: String, val selector: List<String>)

/** Куда ведёт правило: в выход или в балансер — одно из двух. */
internal sealed interface Target {
    data class Out(val tag: String) : Target
    data class Balancer(val tag: String) : Target
}

/** Правило с целью [to]; условия — именованными аргументами, без условия — null. */
internal fun rule(to: Target, match: RuleConf = RuleConf()): RuleConf = when (to) {
    is Target.Out -> match.copy(outboundTag = to.tag)
    is Target.Balancer -> match.copy(balancerTag = to.tag)
}
