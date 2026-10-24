package podbor.driver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import podbor.core.Server
import podbor.core.Service
import podbor.core.ServiceId
import podbor.core.ServerId

/**
 * Где слушает API Xray. Tcp — loopback-порт (JVM на десктопе: соседей-приложений нет); Unix — сокет в личной папке
 * приложения: чужое приложение на телефоне до него не дотянется (права каталога), в отличие от порта на 127.0.0.1.
 */
sealed interface ApiListen {
    data class Tcp(val port: Int = 10085) : ApiListen
    data class Unix(val path: String) : ApiListen
}

/** TUN Xray: нет (анализ подписки, пробы через SOCKS) или на fd туннеля VpnService (сессия; только на Android). */
sealed interface Tun {
    data object Off : Tun
    data class On(val fd: Int) : Tun
}

/** SOCKS-входы кандидатов: открытые (тесты, JVM без соседей) или с паролем процесса. */
sealed interface SocksIn {
    data object Open : SocksIn
    data class Locked(val auth: SocksAuth) : SocksIn
}

/**
 * Параметры конфига: где слушает api ([ApiListen]), порты SOCKS-inbound'ов, каталог geo-файлов, TUN ([Tun]),
 * набор сервисов ([services]: правила и балансер на каждый), UID установленных приложений ([uids]: пакет → UID,
 * правило `process`), уровень лога Xray и замок SOCKS-входов ([socks]).
 */
data class ConfigOpts(
    val api: ApiListen = ApiListen.Tcp(),
    val socksBase: Int = 20000,
    val assetDir: String,
    val tun: Tun = Tun.Off,
    val services: Set<Service> = emptySet(),
    val uids: Map<String, Int> = emptyMap(),
    val logLevel: String = "warning",
    val socks: SocksIn = SocksIn.Open,
    /** Журнал соединений Xray ([connLoop]): только у сессии с TUN — там ходят приложения. */
    val accessLog: AccessLog = AccessLog.Off,
    /** Чем резолвить российские домены: DNS сети или, после ремонта, Яндекс DNS по IP. */
    val ruDns: RuDns = RuDns.System,
)

/** DNS для российских доменов: системный (DNS сети) или Яндекс по IP — когда DNS сети сломан и человек починил. */
sealed interface RuDns {
    data object System : RuDns
    data object Yandex : RuDns
}

/** Яндекс DNS по IP — тот же, что российский эталон линии: в белых списках есть. */
const val YANDEX_DNS_IP = "77.88.8.8"

/** Access-лог Xray: выключен или в файл [File.path] — его читает край ([connLoop]). */
sealed interface AccessLog {
    data object Off : AccessLog
    data class File(val path: String) : AccessLog
}

/** Тег балансера выхода: OverrideBalancerTarget переключает его без перезапуска Xray. */
const val EXIT_BALANCER = "exit"
const val DIRECT = "direct"

/** Тег выхода-заглушки: туда уходит прочее, пока нет ни одного кандидата. */
private const val BLOCK = "block"

/** Тег api — у входа, выхода и правила между ними. */
private const val API = "api"

/** Тег tun-входа. */
private const val TUN_IN = "tun"

/** Российские ресурсы: напрямую и через DNS сети. */
private const val RU_SITES = "geosite:category-ru"
private const val RU_IPS = "geoip:ru"

/** Тег балансера сервиса: выход каждого сервиса переключается отдельно. */
fun balancerOf(service: ServiceId): String = "svc.${service.id}"

/**
 * Тег выхода пары «сервис × сервер» — клон outbound'а сервера (только в сессии с TUN): счётчик Xray у него свой, байты
 * Телеги и Инсты через один сервер не смешиваются, пробы идут выходом сервера и в пульс пары не попадают. Тег НЕ
 * начинается с тега сервера: селектор балансера Xray сравнивает по началу (`strings.HasPrefix`), и `exit` с селектором
 * `s001` забрал бы `s001.tg` себе.
 */
fun pairTag(server: ServerId, service: ServiceId): String = "$PAIR_PREFIX${service.id}.${server.tag}"

/** Начало тега выхода пары: по нему пульс отличает пары от выходов серверов и служебных. */
const val PAIR_PREFIX = "pair."

/** Тег, которым балансер сервиса указывает на сервер: пара — в сессии с TUN, сервер — в анализе; как в [buildConfig]. */
fun exitTagOf(opts: ConfigOpts): (ServerId, ServiceId) -> String = when (opts.tun) {
    Tun.Off -> { server, _ -> server.tag }
    is Tun.On -> ::pairTag
}

/** Тег dns-outbound'а: DNS-запросы телефона из TUN отвечает блок `dns` Xray (только с TUN). */
const val DNS_OUT = "dns-out"

/** MTU TUN: одно значение у VpnService.Builder и у tun-inbound'а Xray. */
const val TUN_MTU = 1500

fun socksPortOf(server: ServerId, servers: List<Server>, opts: ConfigOpts): Int =
    opts.socksBase + servers.indexOfFirst { it.id == server }.also { require(it >= 0) { "нет сервера $server" } }

/**
 * Чистая: кандидаты (outbound'ы с tag = ServerId) → полный конфиг Xray.
 * Порядок правил важен: api, затем inbound кандидата → его outbound (пробы не уходят в DIRECT),
 * затем (только с TUN) порт 53 из tun → dns-outbound, затем российские ресурсы → DIRECT, остальное → балансер выхода.
 */
fun buildConfig(servers: List<Server>, outbounds: Map<ServerId, JsonObject>, opts: ConfigOpts): JsonObject =
    xrayJson.encodeToJsonElement(XrayConf.serializer(), confOf(servers, outbounds, opts)).jsonObject

private fun confOf(servers: List<Server>, outbounds: Map<ServerId, JsonObject>, opts: ConfigOpts) = XrayConf(
    log = LogConf(opts.logLevel, accessOf(opts.accessLog)),
    env = mapOf("xray.location.asset" to opts.assetDir) + tunEnv(opts.tun),
    api = ApiConf(API, listOf("RoutingService", "StatsService")),
    // Счётчики байт входов и выходов — пульс: трафик сессии и проб, в том числе анализа подписки.
    stats = StatsConf,
    policy = PolicyConf(SystemPolicy(statsInboundUplink = true, statsInboundDownlink = true, statsOutboundUplink = true, statsOutboundDownlink = true)),
    dns = DnsConf(listOf(DnsServer(ruDnsAddress(opts.ruDns), listOf(RU_SITES)), DnsServer("1.1.1.1"))),
    inbounds = listOf(apiInbound(opts.api)) + servers.map { socksInbound(it, servers, opts) } + tunInbounds(opts.tun),
    outbounds = servers.map { outbounds.getValue(it.id) } +
        pairsOf(servers, opts).map { (s, svc) -> JsonObject(outbounds.getValue(s.id) + ("tag" to JsonPrimitive(pairTag(s.id, svc.id)))) } +
        builtinOutbounds(opts.tun).map { xrayJson.encodeToJsonElement(BuiltinOutbound.serializer(), it).jsonObject },
    routing = RoutingConf("IPIfNonMatch", rulesOf(servers, opts), balancersOf(servers, opts)),
)

private fun accessOf(a: AccessLog): String? = when (a) {
    AccessLog.Off -> null
    is AccessLog.File -> a.path
}

private fun tunEnv(tun: Tun): Map<String, String> = when (tun) {
    Tun.Off -> emptyMap()
    is Tun.On -> mapOf("xray.tun.fd" to tun.fd.toString())
}

private fun ruDnsAddress(ruDns: RuDns): String = when (ruDns) {
    RuDns.System -> "localhost"
    RuDns.Yandex -> YANDEX_DNS_IP
}

private fun apiInbound(at: ApiListen): InboundConf = when (at) {
    is ApiListen.Tcp -> InboundConf.Api(API, "127.0.0.1", at.port, DokodemoSettings("127.0.0.1"))
    // Путь в listen — Xray слушает Unix-сокет, порт не нужен.
    is ApiListen.Unix -> InboundConf.Api(API, at.path, settings = DokodemoSettings("127.0.0.1"))
}

private fun socksInbound(s: Server, servers: List<Server>, opts: ConfigOpts): InboundConf =
    InboundConf.Socks("in.${s.id.tag}", "127.0.0.1", socksPortOf(s.id, servers, opts), socksSettings(opts.socks))

private fun socksSettings(socks: SocksIn): SocksSettings = when (socks) {
    SocksIn.Open -> SocksSettings(udp = false)
    is SocksIn.Locked -> SocksSettings(udp = false, auth = "password", accounts = listOf(SocksAccount(socks.auth.user, socks.auth.pass)))
}

private fun tunInbounds(tun: Tun): List<InboundConf> = when (tun) {
    Tun.Off -> emptyList()
    is Tun.On -> listOf(
        InboundConf.Tun(
            TUN_IN,
            // Без имени Xray подбирает свободное перебором интерфейсов (netlink), что Android приложению
            // запрещает: «netlinkrib: permission denied». На Android интерфейс берётся из fd, имя не используется.
            TunSettings("podbor0", TUN_MTU),
            // Из TUN приходят IP: без sniffing правило geosite:category-ru для них не срабатывает.
            // routeOnly — домен только для выбора маршрута, соединение идёт на исходный IP.
            Sniffing(enabled = true, destOverride = listOf("http", "tls", "quic"), routeOnly = true),
        ),
    )
}

private fun builtinOutbounds(tun: Tun): List<BuiltinOutbound> =
    listOf(BuiltinOutbound.Freedom(DIRECT), BuiltinOutbound.Blackhole(BLOCK)) + when (tun) {
        Tun.Off -> emptyList()
        is Tun.On -> listOf(BuiltinOutbound.Dns(DNS_OUT))
    }

private fun rulesOf(servers: List<Server>, opts: ConfigOpts): List<RuleConf> =
    listOf(rule(Target.Out(API), RuleConf(inboundTag = listOf(API)))) +
        servers.map { s -> rule(Target.Out(s.id.tag), RuleConf(inboundTag = listOf("in.${s.id.tag}"))) } +
        dnsRules(opts.tun) +
        listOf(
            rule(Target.Out(DIRECT), RuleConf(domain = listOf(RU_SITES))),
            rule(Target.Out(DIRECT), RuleConf(ip = listOf(RU_IPS))),
        ) +
        serviceRules(servers, opts) +
        rule(rest(servers), RuleConf(network = "tcp,udp"))

/** DNS телефона (VpnService отдаёт адрес внутри TUN) — в блок dns Xray, а не в балансер как обычный UDP. */
private fun dnsRules(tun: Tun): List<RuleConf> = when (tun) {
    Tun.Off -> emptyList()
    is Tun.On -> listOf(rule(Target.Out(DNS_OUT), RuleConf(inboundTag = listOf(TUN_IN), port = 53)))
}

/**
 * Правила сервисов: домен > IP > приложение (process), внутри вида — по ServiceId, чтобы порядок набора
 * ничего не менял. На Android libXray отдаёт правилу `process` UID числом; ruleTag виден в логе info.
 */
private fun serviceRules(servers: List<Server>, opts: ConfigOpts): List<RuleConf> =
    if (servers.isEmpty()) emptyList()
    else RuleKind.entries.flatMap { kind ->
        sortedServices(opts).mapNotNull { svc -> kind.ruleFor(svc, opts) }
    }

/** Xray не принимает балансер с пустым selector: без кандидатов прочее — в block, а не ошибка движка. */
private fun rest(servers: List<Server>): Target = if (servers.isEmpty()) Target.Out(BLOCK) else Target.Balancer(EXIT_BALANCER)

private fun balancersOf(servers: List<Server>, opts: ConfigOpts): List<BalancerConf>? =
    if (servers.isEmpty()) null
    else listOf(BalancerConf(EXIT_BALANCER, servers.map { it.id.tag })) +
        sortedServices(opts).map { svc -> BalancerConf(balancerOf(svc.id), servers.map { exitTagOf(opts)(it.id, svc.id) }) }

private fun sortedServices(opts: ConfigOpts): List<Service> = opts.services.sortedBy { it.id.id }

/** Пары «сервер × сервис» — только в сессии с TUN; порядок — сервер, затем сервис по ServiceId. */
private fun pairsOf(servers: List<Server>, opts: ConfigOpts): List<Pair<Server, Service>> = when (opts.tun) {
    Tun.Off -> emptyList()
    is Tun.On -> servers.flatMap { s -> sortedServices(opts).map { s to it } }
}

/** Виды правил сервиса в порядке приоритета: домен > IP > приложение. Значения отсортированы: порядок описания не важен. */
private enum class RuleKind(val suffix: String, val values: (Service, ConfigOpts) -> List<String>, val match: (List<String>) -> RuleConf) {
    Domain("domain", { s, _ -> s.match.domains.sorted().map { "geosite:$it" } }, { RuleConf(domain = it) }),
    Ip("ip", { s, _ -> s.match.ips.sorted().map { "geoip:$it" } }, { RuleConf(ip = it) }),
    App("app", { s, o -> s.match.apps.sorted().filter { it in o.uids }.map { o.uids.getValue(it).toString() } }, { RuleConf(process = it) }),
    ;

    /** Правило вида для сервиса; значений нет (приложение не установлено) — правила нет. */
    fun ruleFor(svc: Service, opts: ConfigOpts): RuleConf? = values(svc, opts).let { vs ->
        if (vs.isEmpty()) null
        else rule(Target.Balancer(balancerOf(svc.id)), match(vs).copy(ruleTag = "svc.${svc.id.id}.$suffix"))
    }
}
