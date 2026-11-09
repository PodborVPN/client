package podbor.driver

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import podbor.core.ExitFilter
import podbor.core.WhiteHint
import podbor.core.ServerFacts
import podbor.core.Seen
import podbor.core.Passport
import podbor.core.Letter
import podbor.core.Entry
import podbor.core.OsNetwork
import podbor.core.ServerId
import podbor.core.Telegram
import podbor.core.rememberSuccess

class CountryFilterDriverTest : FunSpec({
    test("память не восстанавливает исключённый выход") {
        runBlocking {
            val base = World(ips = listOf("203.0.113.1", "203.0.113.2"), ruIps = emptySet(), line = line(podbor.core.Reach.Up, podbor.core.Reach.Up), fitsPorts = emptySet())
            val servers = resourceScope {
                val driver = base.driver()
                with(driver) { warmUp("sub") }.getOrNull()!!.servers
            }
            val memory = podbor.core.Memory.Empty.rememberSuccess(
                podbor.core.memoryKey(OsNetwork.Wifi, podbor.core.Scenario.Wifi, Telegram.id), servers.first(), 1L,
            )
            val world = World(ips = base.ips, ruIps = emptySet(), line = base.line, fitsPorts = emptySet(), memory = memory,
                exitProbe = ExitProbe { error("при запуске VPN пробы страны нет") })
            world.seedExits()
            resourceScope {
                val driver = world.driver()
                val warm = with(driver) { warmUp("sub", ExitFilter(setOf("RU"))) }.getOrNull()!!
                val prior = podbor.core.Prior.Detected(arrow.core.nonEmptyListOf(podbor.core.Letter.LineObserved(System.currentTimeMillis(), base.line)))
                driver.recall(warm, setOf(Telegram), OsNetwork.Wifi, prior) shouldBe emptyMap()
                world.exits shouldBe emptyList()
            }
        }
    }

    test("фильтр оставляет зарубежный выход российского входа и убирает RU из рабочего конфига") {
        val w = World(
            ips = listOf("203.0.113.1", "203.0.113.2", "203.0.113.3"),
            ruIps = setOf("203.0.113.2"), line = line(podbor.core.Reach.Up, podbor.core.Reach.Up), fitsPorts = setOf(20000),
            exitProbe = ExitProbe { error("при запуске VPN пробы страны нет") },
        )
        w.seedExits()
        runBlocking { resourceScope {
            val d = w.driver()
            val warm = with(d) { warmUp("sub", ExitFilter(setOf("RU"))) }.getOrNull()!!
            warm.servers.map { it.id } shouldBe listOf(ServerId("s002"))
            warm.countries shouldBe setOf("RU", "NL")
            d.press(warm, setOf(Telegram), OsNetwork.Wifi)
            w.probed.toSet() shouldBe setOf(20000)
            val config = w.configs.last()
            config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }.filter { it.startsWith("s") } shouldBe listOf("s002")
            config["routing"]!!.jsonObject["balancers"]!!.jsonArray.first().jsonObject["selector"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("s002")
            w.engineRuns shouldBe 1
        } }
    }
    test("если допуск пустой рабочий конфиг блокирует трафик вместо возврата к исключённым") {
        val w = World(ips = listOf("203.0.113.1"), ruIps = emptySet(), line = line(podbor.core.Reach.Up, podbor.core.Reach.Up), fitsPorts = emptySet(),
            exitProbe = ExitProbe { error("при запуске VPN пробы страны нет") })
        w.seedExits()
        runBlocking { resourceScope {
            val d = w.driver()
            val warm = with(d) { warmUp("sub", ExitFilter(setOf("RU"))) }.getOrNull()!!
            warm.servers shouldBe emptyList()
            val routing = w.configs.last()["routing"]!!.jsonObject
            routing["balancers"] shouldBe null
            routing["rules"]!!.jsonArray.last().jsonObject["outboundTag"]!!.jsonPrimitive.content shouldBe "block"
        } }
    }
})


private fun World.seedExits() {
    val resource = requireNotNull(javaClass.getResourceAsStream("/fixtures/tape/filtered-start.jsonl"))
    val observations = resource.bufferedReader().useLines { lines ->
        lines.map { ((rowOf(it) as Row.Step).letter as Letter.ExitSeen).exit }.toList()
    }
    val drafts = draftsOf(ips.mapIndexed(::outbound))
    savedPassport = Passport(drafts.zip(observations).associate { (draft, exit) ->
        draft.key to ServerFacts(draft.transport, Entry.Unknown, WhiteHint.Unlikely, exit, Seen.Answered(1))
    }, 2)
}
