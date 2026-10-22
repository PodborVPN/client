package podbor.app

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import podbor.app.vpn.reporting
import podbor.app.vpn.tunFdOf
import podbor.app.vpn.tunSession
import podbor.driver.EngineError
import podbor.driver.XrayApi
import podbor.driver.XrayEngine

/** Фейковый движок: остановка — запись «stopXray» в журнал из release своего install. */
private class FakeEngine(private val events: MutableList<String>, private val start: Either<EngineError, Unit> = Unit.right()) : XrayEngine {
    override suspend fun ResourceScope.run(config: JsonObject): Either<EngineError, Unit> =
        install({ events += "runXray"; start }, { _, _ -> events += "stopXray" })
}

class TunTest : FunSpec({

    test("tunFdOf: 0 и отрицательные → Left (Xray молча взял бы fd 0), положительный → Right") {
        tunFdOf(0) shouldBe EngineError.Failed("tun fd 0").left()
        tunFdOf(-1) shouldBe EngineError.Failed("tun fd -1").left()
        tunFdOf(42) shouldBe 42.right()
    }

    /** Туннель фейковый: fd 42, release — «closeFd»; прогрев — фейковый движок в той же области. */
    fun ResourceScope.fakeTunnel(events: MutableList<String>, fd: Int = 42): suspend ResourceScope.() -> Either<EngineError, Int> = {
        install({ events += "establish"; fd.right() }, { _, _ -> events += "closeFd" })
    }

    test("порядок release: Xray остановлен раньше, чем закрыт fd") {
        val events = mutableListOf<String>()
        resourceScope {
            tunSession(fakeTunnel(events)) { fd ->
                events += "warmUp($fd)"
                with(FakeEngine(events)) { run(buildJsonObject {}) }
            } shouldBe Unit.right()
            events += "работа"
        }
        events shouldBe listOf("establish", "warmUp(42)", "runXray", "работа", "stopXray", "closeFd")
    }

    test("Xray не поднялся → Left, но порядок release тот же") {
        val events = mutableListOf<String>()
        val failed = EngineError.Failed("runXray")
        resourceScope {
            tunSession(fakeTunnel(events)) { with(FakeEngine(events, failed.left())) { run(buildJsonObject {}) } } shouldBe failed.left()
        }
        events.takeLast(2) shouldBe listOf("stopXray", "closeFd")
    }

    test("fd ≤ 0 от туннеля → Left до прогрева; fd всё равно закрыт") {
        val events = mutableListOf<String>()
        resourceScope {
            tunSession(fakeTunnel(events, fd = 0)) { events += "warmUp"; Unit.right() } shouldBe EngineError.Failed("tun fd 0").left()
        }
        events shouldBe listOf("establish", "closeFd")
    }

    test("reporting: «Xray остановлен» пишется после stopXray и до закрытия fd") {
        val events = mutableListOf<String>()
        resourceScope {
            tunSession(fakeTunnel(events)) {
                with(FakeEngine(events).reporting { events += "Xray остановлен" }) { run(buildJsonObject {}) }
            }
        }
        events.takeLast(3) shouldBe listOf("stopXray", "Xray остановлен", "closeFd")
    }

    test("reporting API: сбой вызова — в отчёт с именем вызова, успех — молча; ответ не меняется") {
        val failed = mutableListOf<String>()
        val boom = EngineError.Failed("UNAVAILABLE")
        val api = object : XrayApi {
            override suspend fun routeOf(ip: String) = "direct".right()
            override suspend fun setExit(balancer: String, tag: String) = boom.left()
            override suspend fun clearExit(balancer: String) = Unit.right()
        }.reporting { failed += it }
        api.routeOf("192.0.2.1") shouldBe "direct".right()
        api.setExit("svc.tg", "s001") shouldBe boom.left()
        api.clearExit("svc.tg") shouldBe Unit.right()
        failed shouldBe listOf("setExit svc.tg → s001: $boom")
    }
})
