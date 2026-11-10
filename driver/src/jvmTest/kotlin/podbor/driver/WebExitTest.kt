package podbor.driver

import arrow.core.nonEmptyListOf
import arrow.core.left
import arrow.core.right
import podbor.core.ChosenOn
import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import podbor.core.Letter
import podbor.core.Memory
import podbor.core.OsNetwork
import podbor.core.Prior
import podbor.core.ProbeSpec
import podbor.core.Reach
import podbor.core.Scenario
import podbor.core.ServerId
import podbor.core.Telegram
import podbor.core.Verdict
import podbor.core.Web
import podbor.core.memoryKey
import podbor.core.rememberSuccess

class WebExitTest : FunSpec({
    val observation = line(Reach.Up, Reach.Up)
    val servers = listOf("203.0.113.1", "203.0.113.2")
    val services = setOf(Telegram, Web)
    val first = ServerId("s001")
    val second = ServerId("s002")
    val probe = Prober { spec, server, _ ->
        when (spec) {
            ProbeSpec.Telegram -> Verdict.TelegramDc(if (server.id == first) 3 else 1)
            ProbeSpec.Instagram -> Verdict.Instagram(false, false)
            ProbeSpec.Https -> Verdict.Https(server.id == second)
        }
    }

    test("выход Telegram не заменяет отдельный общий выход HTTPS") {
        val world = World(servers, emptySet(), observation, emptySet(), prober = probe, services = services)
        runBlocking {
            resourceScope {
                val driver = world.driver()
                val warm = with(driver) { warmUp("sub") }.getOrNull().shouldNotBeNull()
                driver.press(warm, services, OsNetwork.Wifi)
                driver.exits.effective(Telegram.id) shouldBe Route.Fixed(first)
                driver.exits.effective(Web.id) shouldBe Route.Fixed(second)
                driver.exits.state.value.general shouldBe Route.Fixed(second)
                world.exits shouldBe listOf("s002")
            }
        }
    }

    test("отказ Xray назначить общий выход не оставляет ложного закрепления") {
        var fail = true
        val api = object : XrayApi {
            override suspend fun routeOf(ip: String) = DIRECT.right()
            override suspend fun clearExit(balancer: String) = Unit.right()
            override suspend fun setExit(balancer: String, tag: String) =
                if (balancer == EXIT_BALANCER && fail) EngineError.Failed("не принял выход").left()
                else Unit.right()
        }
        runBlocking {
            val exits = Exits(api, services)
            exits.make(Web.id, second, ChosenOn.Unknown).isLeft() shouldBe true
            exits.pinned(Web.id) shouldBe false
            exits.state.value.general shouldBe Route.Free
            fail = false
            exits.make(Web.id, second, ChosenOn.Unknown).isRight() shouldBe true
            exits.pinned(Web.id) shouldBe true
            exits.state.value.general shouldBe Route.Fixed(second)
        }
    }

    test("общий выход из памяти назначается только после новой пробы HTTPS") {
        val base = World(servers, emptySet(), observation, emptySet(), services = services)
        runBlocking {
            val firstServer = resourceScope {
                with(base.driver()) { warmUp("sub") }.getOrNull().shouldNotBeNull().servers.first()
            }
            val memory = Memory.Empty.rememberSuccess(memoryKey(OsNetwork.Wifi, Scenario.Wifi, Web.id), firstServer, 1L)
            val world = World(servers, emptySet(), observation, emptySet(), memory = memory, prober = probe, services = services)
            resourceScope {
                val driver = world.driver()
                val warm = with(driver) { warmUp("sub") }.getOrNull().shouldNotBeNull()
                val prior = Prior.Detected(nonEmptyListOf(Letter.LineObserved(System.currentTimeMillis(), observation)))
                driver.recall(warm, services, OsNetwork.Wifi, prior) shouldBe emptyMap()
                driver.exits.state.value.general shouldBe Route.Free
                world.exits shouldBe emptyList()
            }
        }
    }
})
