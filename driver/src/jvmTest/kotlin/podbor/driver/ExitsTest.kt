package podbor.driver

import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import podbor.core.HARDCODED_SERVICES
import podbor.core.Instagram
import podbor.core.ServerId
import podbor.core.Telegram

/** XrayApi, записывающий каждый OverrideBalancerTarget как «балансер=цель» (снятие — пустая цель). */
private class Overrides : XrayApi {
    val calls = mutableListOf<String>()
    override suspend fun routeOf(ip: String) = DIRECT.right()
    override suspend fun setExit(balancer: String, tag: String) = Unit.right().also { calls += "$balancer=$tag" }
    override suspend fun clearExit(balancer: String) = Unit.right().also { calls += "$balancer=" }
}

class ExitsTest : FunSpec({

    val s1 = ServerId("s1")
    val s2 = ServerId("s2")
    val s3 = ServerId("s3")

    test("make(GENERAL_FOLLOWS) ставит свой балансер, общий и все незакреплённые") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES)
        runBlocking { exits.make(Telegram.id, s1) }
        api.calls shouldContainExactly listOf("svc.tg=s1", "exit=s1", "svc.insta=s1")
        exits.state.value shouldBe ExitsState(general = s1, pinned = mapOf(Telegram.id to s1))
    }

    test("закреплённый сервис не едет за общим выходом") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES)
        runBlocking {
            exits.make(Telegram.id, s1)
            exits.make(Instagram.id, s2)
            exits.make(Telegram.id, s3)
        }
        api.calls shouldContainExactly listOf("svc.tg=s1", "exit=s1", "svc.insta=s1", "svc.insta=s2", "svc.tg=s3", "exit=s3")
        exits.effective(Instagram.id) shouldBe s2
        exits.effective(Telegram.id) shouldBe s3
    }

    test("до своей находки сервис идёт общим выходом и не закреплён; после make — закреплён") {
        val exits = Exits(Overrides(), HARDCODED_SERVICES)
        exits.effective(Instagram.id) shouldBe null
        exits.pinned(Instagram.id) shouldBe false
        runBlocking { exits.make(Telegram.id, s1) }
        exits.effective(Instagram.id) shouldBe s1
        exits.pinned(Instagram.id) shouldBe false
        runBlocking { exits.make(Instagram.id, s2) }
        exits.pinned(Instagram.id) shouldBe true
    }

    test("make не GENERAL_FOLLOWS трогает только свой балансер") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES)
        runBlocking { exits.make(Instagram.id, s2) }
        api.calls shouldContainExactly listOf("svc.insta=s2")
        exits.state.value.general shouldBe null
    }

    test("clear(GENERAL_FOLLOWS) снимает общий; незакреплённые снимаются вместе, закреплённые остаются") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES)
        runBlocking {
            exits.make(Telegram.id, s1)
            api.calls.clear()
            exits.clear(Telegram.id)
        }
        api.calls shouldContainExactly listOf("exit=", "svc.insta=", "svc.tg=")
        exits.state.value shouldBe ExitsState(general = null, pinned = emptyMap())
    }

    test("clear сервиса возвращает его балансер к общему выходу") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES)
        runBlocking {
            exits.make(Telegram.id, s1)
            exits.make(Instagram.id, s2)
            api.calls.clear()
            exits.clear(Instagram.id)
        }
        api.calls shouldContainExactly listOf("svc.insta=s1")
        exits.effective(Instagram.id) shouldBe s1
        exits.pinned(Instagram.id) shouldBe false
    }

    test("exitsPinPairTag: балансер сервиса — тег пары, общий выход — тег сервера") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES, ::pairTag)
        runBlocking { exits.make(Telegram.id, s1) }
        api.calls shouldContainExactly listOf("svc.tg=pair.tg.s1", "exit=s1", "svc.insta=pair.insta.s1")
        exits.state.value shouldBe ExitsState(general = s1, pinned = mapOf(Telegram.id to s1))
    }

    test("clear сервиса с парами возвращает его балансер к паре общего выхода") {
        val api = Overrides()
        val exits = Exits(api, HARDCODED_SERVICES, ::pairTag)
        runBlocking {
            exits.make(Telegram.id, s1)
            exits.make(Instagram.id, s2)
            api.calls.clear()
            exits.clear(Instagram.id)
        }
        api.calls shouldContainExactly listOf("svc.insta=pair.insta.s1")
    }
})
