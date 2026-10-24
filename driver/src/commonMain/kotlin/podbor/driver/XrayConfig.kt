package podbor.driver

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
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

/**
 * Параметры конфига: где слушает api ([ApiListen]), порты SOCKS-inbound'ов, каталог geo-файлов, fd TUN (только на Android),
 * набор сервисов ([services]: правила и балансер на каждый), UID установленных приложений ([uids]: пакет → UID,
 * правило `process`), уровень лога Xray и пароль SOCKS-входов ([socksAuth]).
 */
data class ConfigOpts(
    val api: ApiListen = ApiListen.Tcp(),
    val socksBase: Int = 20000,
    val assetDir: String,
    val tunFd: Int? = null,
    val services: Set<Service> = emptySet(),
    val uids: Map<String, Int> = emptyMap(),
    val logLevel: String = "warning",
    /** Пароль SOCKS-входов кандидатов; null — без пароля (тесты, JVM без соседей). */
    val socksAuth: SocksAuth? = null,
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
fun exitTagOf(opts: ConfigOpts): (ServerId, ServiceId) -> String =
    if (opts.tunFd != null) ::pairTag else { server, _ -> server.tag }

/** Тег dns-outbound'а: DNS-запросы телефона из TUN отвечает блок `dns` Xray (только при tunFd). */
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
    buildJsonObject {
        putJsonObject("log") {
            put("loglevel", opts.logLevel)
            when (val a = opts.accessLog) {
                AccessLog.Off -> Unit
                is AccessLog.File -> put("access", a.path)
            }
        }
        putJsonObject("env") {
            put("xray.location.asset", opts.assetDir)
            opts.tunFd?.let { put("xray.tun.fd", it.toString()) }
        }
        putJsonObject("api") {
            put("tag", "api")
            putJsonArray("services") {
                add("RoutingService")
                add("StatsService")
            }
        }
        // Счётчики байт входов и выходов — пульс: трафик сессии и проб, в том числе анализа подписки.
        putJsonObject("stats") {}
        putJsonObject("policy") {
            putJsonObject("system") {
                put("statsInboundUplink", true)
                put("statsInboundDownlink", true)
                put("statsOutboundUplink", true)
                put("statsOutboundDownlink", true)
            }
        }
        putJsonObject("dns") {
            putJsonArray("servers") {
                addJsonObject {
                    put(
                        "address",
                        when (opts.ruDns) {
                            RuDns.System -> "localhost"
                            RuDns.Yandex -> YANDEX_DNS_IP
                        },
                    )
                    putJsonArray("domains") { add("geosite:category-ru") }
                }
                add("1.1.1.1")
            }
        }
        putJsonArray("inbounds") {
            addJsonObject {
                put("tag", "api")
                put("protocol", "dokodemo-door")
                when (val at = opts.api) {
                    is ApiListen.Tcp -> {
                        put("listen", "127.0.0.1")
                        put("port", at.port)
                    }
                    // Путь в listen — Xray слушает Unix-сокет, порт не нужен.
                    is ApiListen.Unix -> put("listen", at.path)
                }
                putJsonObject("settings") { put("address", "127.0.0.1") }
            }
            servers.forEach { s ->
                addJsonObject {
                    put("tag", "in.${s.id.tag}")
                    put("protocol", "socks")
                    put("listen", "127.0.0.1")
                    put("port", socksPortOf(s.id, servers, opts))
                    putJsonObject("settings") {
                        put("udp", false)
                        opts.socksAuth?.let { a ->
                            put("auth", "password")
                            putJsonArray("accounts") { addJsonObject { put("user", a.user); put("pass", a.pass) } }
                        }
                    }
                }
            }
            opts.tunFd?.let {
                addJsonObject {
                    put("tag", "tun")
                    put("protocol", "tun")
                    putJsonObject("settings") {
                        // Без имени Xray подбирает свободное перебором интерфейсов (netlink), что Android приложению
                        // запрещает: «netlinkrib: permission denied». На Android интерфейс берётся из fd, имя не используется.
                        put("name", "podbor0")
                        put("mtu", TUN_MTU)
                    }
                    // Из TUN приходят IP: без sniffing правило geosite:category-ru для них не срабатывает.
                    // routeOnly — домен только для выбора маршрута, соединение идёт на исходный IP.
                    putJsonObject("sniffing") {
                        put("enabled", true)
                        putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                        put("routeOnly", true)
                    }
                }
            }
        }
        putJsonArray("outbounds") {
            servers.forEach { s -> add(outbounds.getValue(s.id)) }
            pairsOf(servers, opts).forEach { (s, svc) ->
                add(JsonObject(outbounds.getValue(s.id) + ("tag" to JsonPrimitive(pairTag(s.id, svc.id)))))
            }
            addJsonObject { put("tag", DIRECT); put("protocol", "freedom") }
            addJsonObject { put("tag", "block"); put("protocol", "blackhole") }
            opts.tunFd?.let { addJsonObject { put("tag", DNS_OUT); put("protocol", "dns") } }
        }
        putJsonObject("routing") {
            put("domainStrategy", "IPIfNonMatch")
            putJsonArray("rules") {
                addJsonObject { inboundTag("api"); put("outboundTag", "api") }
                servers.forEach { s -> addJsonObject { inboundTag("in.${s.id.tag}"); put("outboundTag", s.id.tag) } }
                // DNS телефона (VpnService отдаёт адрес внутри TUN) — в блок dns Xray, а не в балансер как обычный UDP.
                opts.tunFd?.let { addJsonObject { inboundTag("tun"); put("port", 53); put("outboundTag", DNS_OUT) } }
                addJsonObject { putJsonArray("domain") { add("geosite:category-ru") }; put("outboundTag", DIRECT) }
                addJsonObject { putJsonArray("ip") { add("geoip:ru") }; put("outboundTag", DIRECT) }
                // Правила сервисов: домен > IP > приложение (process), внутри вида — по ServiceId, чтобы порядок набора
                // ничего не менял. На Android libXray отдаёт правилу `process` UID числом; ruleTag виден в логе info.
                if (servers.isNotEmpty()) {
                    val sorted = opts.services.sortedBy { it.id.id }
                    for (kind in RuleKind.entries) sorted.forEach { svc ->
                        val values = kind.values(svc, opts)
                        if (values.isNotEmpty()) addJsonObject {
                            put("ruleTag", "svc.${svc.id.id}.${kind.suffix}")
                            putJsonArray(kind.key) { values.forEach { add(it) } }
                            put("balancerTag", balancerOf(svc.id))
                        }
                    }
                }
                // Xray не принимает балансер с пустым selector: без кандидатов прочее — в block, а не ошибка движка.
                if (servers.isEmpty()) {
                    addJsonObject { put("network", "tcp,udp"); put("outboundTag", "block") }
                } else {
                    addJsonObject { put("network", "tcp,udp"); put("balancerTag", EXIT_BALANCER) }
                }
            }
            if (servers.isNotEmpty()) {
                putJsonArray("balancers") {
                    addJsonObject {
                        put("tag", EXIT_BALANCER)
                        putJsonArray("selector") { servers.forEach { add(it.id.tag) } }
                    }
                    opts.services.sortedBy { it.id.id }.forEach { svc ->
                        addJsonObject {
                            put("tag", balancerOf(svc.id))
                            putJsonArray("selector") {
                                servers.forEach { add(exitTagOf(opts)(it.id, svc.id)) }
                            }
                        }
                    }
                }
            }
        }
    }

/** Пары «сервер × сервис» — только в сессии с TUN; порядок — сервер, затем сервис по ServiceId. */
private fun pairsOf(servers: List<Server>, opts: ConfigOpts): List<Pair<Server, Service>> =
    if (opts.tunFd == null) emptyList() else servers.flatMap { s -> opts.services.sortedBy { it.id.id }.map { s to it } }

private fun JsonObjectBuilder.inboundTag(tag: String) = putJsonArray("inboundTag") { add(tag) }

/** Виды правил сервиса в порядке приоритета: домен > IP > приложение. Значения отсортированы: порядок описания не важен. */
private enum class RuleKind(val suffix: String, val key: String, val values: (Service, ConfigOpts) -> List<String>) {
    Domain("domain", "domain", { s, _ -> s.match.domains.sorted().map { "geosite:$it" } }),
    Ip("ip", "ip", { s, _ -> s.match.ips.sorted().map { "geoip:$it" } }),
    App("app", "process", { s, o -> s.match.apps.sorted().mapNotNull { o.uids[it]?.toString() } }),
}
