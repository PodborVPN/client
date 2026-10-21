package podbor.driver

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import arrow.fx.coroutines.resourceScope
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import podbor.core.Endpoint
import podbor.core.OsNetwork
import podbor.core.Prior
import podbor.core.Memory
import podbor.core.Reach
import podbor.core.Telegram
import kotlinx.serialization.json.JsonPrimitive

/** Review Focus 2: движок не стартовал или завис — Left за срок, а не вечное ожидание или стектрейс. */
class EngineFailuresTest : FunSpec({

    test("конвертер завис → Left за таймаут") {
        val started = System.currentTimeMillis()
        runBlocking { LibXrayCli(command = listOf("sleep", "10"), timeoutSec = 1).convert("x") }.shouldBeInstanceOf<Either.Left<EngineError>>()
        (System.currentTimeMillis() - started) shouldBeLessThan 3_000L
    }

    test("бинаря конвертера нет → Left, не исключение") {
        runBlocking { LibXrayCli(command = listOf("/nonexistent-podbor-bin")).convert("x") }.shouldBeInstanceOf<Either.Left<EngineError>>()
    }

    test("docker нет → XrayProcess.run даёт Left, не исключение") {
        val dir = File(System.getProperty("java.io.tmpdir"))
        runBlocking { resourceScope { with(XrayProcess(hostDir = dir, dockerBin = "/nonexistent-podbor-docker")) { run(buildJsonObject {}) } } }
            .shouldBeInstanceOf<Either.Left<EngineError>>()
    }

    test("docker висит → XrayProcess.run даёт Left за срок") {
        val dir = File(System.getProperty("java.io.tmpdir"))
        val fakeDocker = File.createTempFile("fake-docker", ".sh").apply { writeText("#!/bin/sh\nsleep 30\n"); setExecutable(true) }
        val started = System.currentTimeMillis()
        runBlocking { resourceScope { with(XrayProcess(hostDir = dir, dockerBin = fakeDocker.path, commandTimeoutSec = 1, readyTimeoutMs = 500)) { run(buildJsonObject {}) } } }
            .shouldBeInstanceOf<Either.Left<EngineError>>()
        (System.currentTimeMillis() - started) shouldBeLessThan 6_000L
    }

    test("Xray гасится, даже если нажатие упало исключением") {
        var released = false
        val driver = Driver(
            converter = { _ ->
                listOf(buildJsonObject {
                    put("protocol", JsonPrimitive("vless")); put("tag", JsonPrimitive("a"))
                    put("settings", buildJsonObject { put("address", JsonPrimitive("10.0.0.3")); put("port", JsonPrimitive(443)); put("id", JsonPrimitive("u")) })
                }).right()
            },
            engine = XrayEngine { _ -> install({ }, { _, _ -> released = true }); Unit.right() },
            api = object : XrayApi {
                override suspend fun routeOf(ip: String) = "s001".right()
                override suspend fun setExit(balancer: String, tag: String) = Unit.right()
                override suspend fun clearExit(balancer: String) = Unit.right()
            },
            prober = { _, _, _ -> error("проба упала") },
            exitProbe = ExitProbe { null },
            gate = EndpointGate(),
            resolver = { host -> host },
            line = { line(Reach.Up, Reach.Up) },
            clock = Clock { System.currentTimeMillis() },
            store = object : Store {
                override fun loadMemory() = Memory.Empty
                override fun saveMemory(m: Memory) = Unit
            },
            passportStore = InMemoryPassportStore(),
            tape = { _ -> },
            opts = ConfigOpts(assetDir = "/assets"),
        )
        shouldThrow<IllegalStateException> {
            runBlocking { resourceScope { with(driver) { warmUp("подписка") }.map { driver.press(it, setOf(Telegram), OsNetwork.Cellular("MTS", "25001"), prior = Prior.None) } } }
        }
        released shouldBe true
    }

    test("отмена TelegramProbeImpl не превращается в Silent: askDc бросает, а не отвечает") {
        ServerSocket(0).use { silent ->
            // Слушающий сокет принимает соединение (backlog) и молчит: проба застревает на чтении.
            var returned = false
            runBlocking {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    TelegramProbeImpl(deadlineMs = 1_500).askDc(silent.localPort, "149.154.167.51", 443)
                    returned = true
                }
                delay(200)
                job.cancel()
                job.join()
            }
            returned shouldBe false
        }
    }

    test("отмена SocketTcpReach не превращается в Down") {
        var returned = false
        val hanging = object : Socket() {
            override fun connect(endpoint: java.net.SocketAddress, timeout: Int) { Thread.sleep(10_000) }
        }
        runBlocking {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                SocketTcpReach(socketFactory = { hanging }).reach(Endpoint("10.0.0.1", 443), 10_000)
                returned = true
            }
            delay(200)
            job.cancel()
            job.join()
        }
        returned shouldBe false
    }
})
