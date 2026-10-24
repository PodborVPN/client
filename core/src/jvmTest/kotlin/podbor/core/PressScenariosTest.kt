package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

/** Сценарии нажатия: подменённый мир → ядро → дела, объяснение, экран. */
class PressScenariosTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)
    val open = line(Reach.Up, Reach.Up)

    fun pressed(servers: List<Server>, os: OsNetwork = mts, memory: Memory = Memory.Empty, at: Long = 0) =
        Letter.Pressed(atMs = at, os = os, servers = servers, currentExit = null, memory = memory)

    fun id(n: Int) = ServerId("s%03d".format(n))
    fun lastMemory(run: Run) = run.deeds.filterIsInstance<Deed.Remember>().last().memory

    val ru3 = (1..3).map { server("s%03d".format(it), Entry.Ru, name = "RU-$it -> NL") }
    val foreign3 = (4..6).map { server("s%03d".format(it), Entry.Foreign("NL")) }

    test("1. белые списки, 3 Ru, второй проходит → выход второй, зарубежные не пробовались") {
        val run = runPress(pressed(ru3 + foreign3), FakeWorld(whitelist, mapOf(id(2) to Verdict.TelegramDc(3))))
        run.deeds shouldContain Deed.MakeExit(id(2))
        run.probed.intersect(setOf(id(4), id(5), id(6))).shouldBeEmpty()
    }

    test("2. белые списки, Ru нет → NoAdmissibleInConfig, ни одной пробы") {
        val run = runPress(pressed(foreign3), FakeWorld(whitelist, emptyMap()))
        explain(run.letters).outcome shouldBe Outcome.NotFound(Conclusion.NoAdmissibleInConfig)
        run.probed.shouldBeEmpty()
    }

    test("3. чистая сеть, первый проходит → ранний стоп, не больше 6 проб") {
        val servers = (1..12).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val run = runPress(pressed(servers), FakeWorld(open, mapOf(id(1) to Verdict.TelegramDc(5))))
        run.probed.size shouldBeLessThanOrEqual 6
        run.deeds shouldContain Deed.MakeExit(id(1))
    }

    test("4. посреди перебора в чистой сети все упали → линия заново → белые списки → только Ru") {
        val servers = (1..12).map { server("s%03d".format(it), Entry.Foreign("NL")) } +
            listOf(server("s013", Entry.Ru), server("s014", Entry.Ru))
        val run = runPress(pressed(servers), FakeWorld(open, emptyMap(), lineAfter = 4 to whitelist))
        explain(run.letters).diagnosis.scene shouldBe Scene.Chosen(Scenario.MobileWhitelist)
        run.probed.drop(9).shouldContainExactly(id(13), id(14))
    }

    test("5. TlsSilence на :8443 → тот же сервер на :2096; :8443 не трогается 10 мин и в следующих нажатиях") {
        val a8443 = server("s001", Entry.Ru, host = "10.7.0.1", port = 8443)
        val a2096 = server("s002", Entry.Ru, host = "10.7.0.1", port = 2096)
        val others = (3..10).map { server("s%03d".format(it), Entry.Ru, host = "10.7.1.$it") }
        val servers = listOf(a8443, a2096) + others
        val first = runPress(pressed(servers), FakeWorld(whitelist, mapOf(id(1) to Verdict.TlsSilence)))
        first.probed shouldContain id(2)
        val memory = lastMemory(first)
        runPress(pressed(servers, memory = memory, at = 300_000), FakeWorld(whitelist, emptyMap())).probed shouldNotContain id(1)
        runPress(pressed(servers, memory = memory, at = 660_000), FakeWorld(whitelist, emptyMap())).probed shouldContain id(1)
    }

    test("6. 20 серверов на одном IP:порт → одновременно ≤ 1 кандидат (= 3 рукопожатия пробы)") {
        val servers = (1..20).map { server("s%03d".format(it), Entry.Foreign("NL"), host = "10.8.0.1", port = 443) }
        runPress(pressed(servers), FakeWorld(open, emptyMap())).maxInFlightPerEndpoint shouldBeLessThanOrEqual 1
    }

    test("7. нет сети → NoNetwork, ни одной пробы") {
        val run = runPress(pressed(ru3), FakeWorld(line(Reach.Down, Reach.Down), emptyMap()))
        explain(run.letters).outcome shouldBe Outcome.NotFound(Conclusion.NoNetwork)
        run.probed.shouldBeEmpty()
    }

    test("8. память: прошлый успех в том же сценарии у того же оператора пробуется первым") {
        val servers = (1..9).map { server("s%03d".format(it), Entry.Ru) }
        val memory = Memory.Empty.rememberSuccess(MemoryKey("MTS", Scenario.MobileWhitelist), servers[8], atMs = 0)
        runPress(pressed(servers, memory = memory, at = 1_000), FakeWorld(whitelist, emptyMap())).probed.first() shouldBe id(9)
    }

    test("9. Wi-Fi ведёт себя как чистая сеть; Wi-Fi с белым списком на линии → MobileWhitelist") {
        val servers = ru3 + foreign3
        val world = FakeWorld(open, emptyMap())
        runPress(pressed(servers, os = OsNetwork.Wifi), world).probed shouldBe runPress(pressed(servers), world).probed
        explain(runPress(pressed(servers, os = OsNetwork.Wifi), FakeWorld(whitelist, emptyMap())).letters).diagnosis.scene shouldBe
            Scene.Chosen(Scenario.MobileWhitelist)
    }
})
