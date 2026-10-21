package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import podbor.core.Endpoint
import podbor.core.Entry
import podbor.core.Instagram
import podbor.core.ProbeSpec
import podbor.core.ServiceId
import podbor.core.service
import podbor.core.Server
import podbor.core.ServerId
import podbor.core.Telegram
import podbor.core.Transport

class XrayConfigTest : FunSpec({

    fun server(n: Int) = Server(ServerId("s%03d".format(n)), "name$n", Endpoint("10.0.0.$n", 443), Transport.Grpc, "chrome", Entry.Unknown, "k$n")
    val servers = (1..3).map(::server)
    val outbounds = servers.associate { it.id to buildJsonObject { put("protocol", "vless"); put("tag", it.id.tag) } }
    val opts = ConfigOpts(assetDir = "/assets")
    val cfg = buildConfig(servers, outbounds, opts)

    val rules = cfg["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    fun JsonObject.strs(k: String) = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    fun indexOf(pred: (JsonObject) -> Boolean) = rules.indexOfFirst(pred)

    test("inbound кандидата ведёт в свой outbound раньше geo-правил") {
        val candidate = indexOf { it.strs("inboundTag") == listOf("in.s002") }
        rules[candidate]["outboundTag"] shouldBe JsonPrimitive("s002")
        candidate shouldBeLessThan indexOf { "geoip:ru" in it.strs("ip") }
        candidate shouldBeLessThan indexOf { "geosite:category-ru" in it.strs("domain") }
    }

    test("российский домен и geoip:ru → direct, остальное → балансер exit") {
        rules[indexOf { "geosite:category-ru" in it.strs("domain") }]["outboundTag"] shouldBe JsonPrimitive("direct")
        rules[indexOf { "geoip:ru" in it.strs("ip") }]["outboundTag"] shouldBe JsonPrimitive("direct")
        rules.last()["balancerTag"] shouldBe JsonPrimitive("exit")
        val balancer = cfg["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        balancer.strs("selector") shouldBe listOf("s001", "s002", "s003")
        cfg["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content } shouldBe
            listOf("s001", "s002", "s003", "direct", "block")
    }

    test("DNS: российские домены — локальный резолвер, прочее — 1.1.1.1") {
        val dns = cfg["dns"]!!.jsonObject["servers"]!!.jsonArray
        dns[0].jsonObject.strs("domains") shouldBe listOf("geosite:category-ru")
        dns[0].jsonObject["address"] shouldBe JsonPrimitive("localhost")
        dns[1] shouldBe JsonPrimitive("1.1.1.1")
    }

    test("SOCKS-входы кандидатов — с паролем процесса, если он задан; без него — как было") {
        val auth = SocksAuth("u", "p")
        val socks = buildConfig(servers, outbounds, opts.copy(socksAuth = auth))["inbounds"]!!.jsonArray.map { it.jsonObject }
            .filter { it["protocol"]!!.jsonPrimitive.content == "socks" }
        socks.size shouldBe 3
        socks.forEach { inb ->
            val st = inb["settings"]!!.jsonObject
            st["auth"]!!.jsonPrimitive.content shouldBe "password"
            val acc = st["accounts"]!!.jsonArray.single().jsonObject
            acc["user"]!!.jsonPrimitive.content shouldBe "u"
            acc["pass"]!!.jsonPrimitive.content shouldBe "p"
        }
        cfg["inbounds"]!!.jsonArray.map { it.jsonObject }.filter { it["protocol"]!!.jsonPrimitive.content == "socks" }
            .forEach { it["settings"]!!.jsonObject["auth"] shouldBe null }
    }

    test("api-inbound и RoutingService есть") {
        cfg["api"]!!.jsonObject.strs("services") shouldContain "RoutingService"
        val api = cfg["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive("api") }
        api["port"] shouldBe JsonPrimitive(10085)
        api["listen"] shouldBe JsonPrimitive("127.0.0.1")
        rules.first().strs("inboundTag") shouldBe listOf("api")
    }

    test("env: каталог geo-файлов; без tunFd tun-inbound нет") {
        cfg["env"]!!.jsonObject["xray.location.asset"] shouldBe JsonPrimitive("/assets")
        cfg["inbounds"]!!.jsonArray.none { it.jsonObject["protocol"] == JsonPrimitive("tun") } shouldBe true
    }

    test("tunFd → tun-inbound и env xray.tun.fd") {
        val tun = buildConfig(servers, outbounds, opts.copy(tunFd = 42))
        tun["env"]!!.jsonObject["xray.tun.fd"] shouldBe JsonPrimitive("42")
        tun["inbounds"]!!.jsonArray.count { it.jsonObject["protocol"] == JsonPrimitive("tun") } shouldBe 1
    }

    test("tunFd → MTU tun-inbound'а = TUN_MTU (то же значение, что у VpnService.Builder)") {
        val tun = buildConfig(servers, outbounds, opts.copy(tunFd = 42))
        val inbound = tun["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive("tun") }
        inbound["settings"]!!.jsonObject["mtu"] shouldBe JsonPrimitive(TUN_MTU)
        TUN_MTU shouldBe 1500
    }

    test("tunFd → имя tun-inbound'а задано: без него Xray перебирает интерфейсы (netlink), а Android приложению это запрещает") {
        val tun = buildConfig(servers, outbounds, opts.copy(tunFd = 42))
        val inbound = tun["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive("tun") }
        inbound["settings"]!!.jsonObject["name"] shouldBe JsonPrimitive("podbor0")
    }

    test("tunFd → DNS телефона (порт 53 из tun) уходит в dns-outbound раньше geo-правил и балансера") {
        val tun = buildConfig(servers, outbounds, opts.copy(tunFd = 42))
        val dnsOut = tun["outbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive(DNS_OUT) }
        dnsOut["protocol"] shouldBe JsonPrimitive("dns")
        val tunRules = tun["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val dnsRule = tunRules.indexOfFirst { it.strs("inboundTag") == listOf("tun") && it["port"] == JsonPrimitive(53) }
        tunRules[dnsRule]["outboundTag"] shouldBe JsonPrimitive(DNS_OUT)
        dnsRule shouldBeLessThan tunRules.indexOfFirst { "geoip:ru" in it.strs("ip") }
        dnsRule shouldBeLessThan tunRules.indexOfFirst { it["balancerTag"] != null }
    }

    test("tunFd → sniffing на tun-inbound (только для маршрута): geosite:category-ru срабатывает и для трафика из TUN") {
        val tun = buildConfig(servers, outbounds, opts.copy(tunFd = 42))
        val inbound = tun["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive("tun") }
        val sniffing = inbound["sniffing"]!!.jsonObject
        sniffing["enabled"] shouldBe JsonPrimitive(true)
        sniffing.strs("destOverride") shouldBe listOf("http", "tls", "quic")
        sniffing["routeOnly"] shouldBe JsonPrimitive(true)
    }

    val tgUids = mapOf("org.telegram.messenger" to 10304)
    fun rulesOf(o: ConfigOpts) = buildConfig(servers, outbounds, o)["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
    fun ruleTagged(rs: List<JsonObject>, tag: String) = rs.indexOfFirst { it["ruleTag"] == JsonPrimitive(tag) }

    test("сервисы → правила: все domain < все ip < все process, после geoip:ru, последнее — exit") {
        val rs = rulesOf(opts.copy(services = setOf(Telegram, Instagram), uids = tgUids))
        val svc = rs.indices.filter { (rs[it]["ruleTag"] as? JsonPrimitive)?.content?.startsWith("svc.") == true }
        val kind = svc.map { rs[it]["ruleTag"]!!.jsonPrimitive.content.substringAfterLast('.') }
        kind shouldBe listOf("domain", "domain", "ip", "ip", "app")
        rs.indexOfFirst { "geoip:ru" in it.strs("ip") } shouldBeLessThan svc.first()
        rs.last()["balancerTag"] shouldBe JsonPrimitive(EXIT_BALANCER)
        rs.last()["ruleTag"] shouldBe null
    }

    test("содержимое правил: Инста — домены и geoip:facebook без process; Телега — process по UID") {
        val rs = rulesOf(opts.copy(services = setOf(Telegram, Instagram), uids = tgUids))
        rs[ruleTagged(rs, "svc.insta.domain")].strs("domain") shouldBe listOf("geosite:facebook", "geosite:instagram")
        rs[ruleTagged(rs, "svc.insta.domain")]["balancerTag"] shouldBe JsonPrimitive("svc.insta")
        rs[ruleTagged(rs, "svc.insta.ip")].strs("ip") shouldBe listOf("geoip:facebook")
        rs[ruleTagged(rs, "svc.insta.ip")]["balancerTag"] shouldBe JsonPrimitive("svc.insta")
        ruleTagged(rs, "svc.insta.app") shouldBe -1
        rs[ruleTagged(rs, "svc.tg.app")].strs("process") shouldBe listOf("10304")
        rs[ruleTagged(rs, "svc.tg.app")]["balancerTag"] shouldBe JsonPrimitive("svc.tg")
        rs[ruleTagged(rs, "svc.tg.ip")].strs("ip") shouldBe listOf("geoip:telegram")
    }

    test("балансеры svc.insta, svc.tg и exit с одним selector") {
        val bs = buildConfig(servers, outbounds, opts.copy(services = setOf(Telegram, Instagram)))["routing"]!!
            .jsonObject["balancers"]!!.jsonArray.map { it.jsonObject }
        bs.map { it["tag"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("svc.insta", "svc.tg", EXIT_BALANCER)
        bs.map { it.strs("selector") }.toSet() shouldBe setOf(servers.map { it.id.tag })
        balancerOf(Telegram.id) shouldBe "svc.tg"
    }

    test("порядок набора и дубликаты не меняют конфиг") {
        checkAll(Arb.shuffle(listOf(Telegram, Instagram))) { order ->
            buildConfig(servers, outbounds, opts.copy(services = order.toCollection(LinkedHashSet()), uids = tgUids)) shouldBe
                buildConfig(servers, outbounds, opts.copy(services = setOf(Telegram, Instagram), uids = tgUids))
        }
        buildConfig(servers, outbounds, opts.copy(services = setOf(Telegram))) shouldBe
            buildConfig(servers, outbounds, opts.copy(services = setOf(Telegram, Telegram.copy())))
    }

    test("порядок доменов и IP внутри описания сервиса не меняет конфиг: значения отсортированы") {
        fun svc(vararg names: String) = service(ServiceId("x"), "X", "Икс", probe = ProbeSpec.Telegram) {
            match { domains(*names); ips(*names); app("b.app"); app("a.app") }
        }
        checkAll(Arb.shuffle(listOf("c", "a", "b"))) { order ->
            val rs = rulesOf(opts.copy(services = setOf(svc(*order.toTypedArray()))))
            rs[ruleTagged(rs, "svc.x.domain")].strs("domain") shouldBe listOf("geosite:a", "geosite:b", "geosite:c")
            rs[ruleTagged(rs, "svc.x.ip")].strs("ip") shouldBe listOf("geoip:a", "geoip:b", "geoip:c")
        }
    }

    test("UID Телеги + домен Инсты: доменное правило Инсты раньше process Телеги") {
        val rs = rulesOf(opts.copy(services = setOf(Telegram, Instagram), uids = tgUids))
        ruleTagged(rs, "svc.insta.domain") shouldBeLessThan ruleTagged(rs, "svc.tg.app")
    }

    test("без services правил и балансеров svc.* нет; уровень лога — warning, если не задан") {
        for (r in rules) (r["ruleTag"] as? JsonPrimitive)?.content?.startsWith("svc.") shouldBe null
        val bs = cfg["routing"]!!.jsonObject["balancers"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        bs shouldBe listOf(EXIT_BALANCER)
        rules.none { it["process"] != null } shouldBe true
        cfg["log"]!!.jsonObject["loglevel"] shouldBe JsonPrimitive("warning")
        buildConfig(servers, outbounds, opts.copy(logLevel = "info"))["log"]!!.jsonObject["loglevel"] shouldBe JsonPrimitive("info")
    }

    test("без tunFd dns-outbound'а и правила порта 53 нет") {
        cfg["outbounds"]!!.jsonArray.none { it.jsonObject["protocol"] == JsonPrimitive("dns") } shouldBe true
        rules.none { it["port"] != null } shouldBe true
    }

    test("socksPortOf: s001 → 20000, s003 → 20002; inbound на этом порту") {
        socksPortOf(ServerId("s001"), servers, opts) shouldBe 20000
        socksPortOf(ServerId("s003"), servers, opts) shouldBe 20002
        val socks = cfg["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"] == JsonPrimitive("in.s003") }
        socks["port"] shouldBe JsonPrimitive(20002)
        socks["protocol"] shouldBe JsonPrimitive("socks")
    }

    test("ни одного кандидата → без балансера (Xray не принимает пустой selector), остальное — в block") {
        val empty = buildConfig(emptyList(), emptyMap(), opts)
        (empty["routing"]!!.jsonObject["balancers"] == null || empty["routing"]!!.jsonObject["balancers"]!!.jsonArray.isEmpty()) shouldBe true
        empty["routing"]!!.jsonObject["rules"]!!.jsonArray.last().jsonObject["outboundTag"] shouldBe JsonPrimitive("block")
    }
})
