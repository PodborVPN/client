package podbor.linux

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

class XrayBinaryTest : FunSpec({
    test("без супервизора движок не запускается напрямую") {
        val dir = Files.createTempDirectory("xray-owner-test").toFile()
        try {
            val marker = File(dir, "started")
            val binary = File(dir, "engine").apply {
                writeText("#!/bin/sh\ntouch '${marker.path}'\nexit 4\n")
                setExecutable(true)
            }
            val port = ServerSocket(0).use { it.localPort }
            val result = resourceScope {
                with(XrayBinary(binary.path, dir, port, owner = File(dir, "absent-owner").path)) {
                    run(buildJsonObject {})
                }
            }
            result.isLeft() shouldBe true
            marker.exists() shouldBe false
        } finally {
            dir.deleteRecursively()
        }
    }
    test("закрытие ресурса передаёт EOF и освобождает порт для повторного запуска") {
        withEngineFixture { fixture ->
            repeat(2) {
                fixture.eof.delete()
                resourceScope {
                    with(fixture.engine()) { run(fixture.config()) }.isRight() shouldBe true
                    fixture.childAlive() shouldBe true
                }
                fixture.eof.exists() shouldBe true
                fixture.childAlive() shouldBe false
                ServerSocket(fixture.port).close()
            }
        }
    }

    test("отмена живого ресурса дожидается остановки движка") {
        withEngineFixture { fixture ->
            coroutineScope {
                val ready = CompletableDeferred<Unit>()
                val job = launch {
                    resourceScope {
                        with(fixture.engine()) { run(fixture.config()) }.isRight() shouldBe true
                        ready.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(12_000) { ready.await() }
                job.cancelAndJoin()
                fixture.eof.exists() shouldBe true
                fixture.childAlive() shouldBe false
            }
        }
    }

    test("отмена во время ожидания готовности не оставляет движок") {
        withEngineFixture { fixture ->
            coroutineScope {
                val job = launch {
                    resourceScope {
                        with(fixture.engine()) { run(fixture.config(starting = true)) }
                    }
                }
                withTimeout(12_000) {
                    while (!fixture.started.exists()) delay(20)
                }
                withTimeout(12_000) { job.cancelAndJoin() }
                fixture.eof.exists() shouldBe true
                fixture.childAlive() shouldBe false
            }
        }
    }
})

private class EngineFixture(val dir: File) {
    val port = ServerSocket(0).use { it.localPort }
    val started = File(dir, "started")
    val eof = File(dir, "owner-eof")
    private val processes = mutableListOf<Process>()
    private val binary = script("engine.py")
    private val owner = script("owner.py")

    private fun script(name: String): File = File(dir, name).apply {
        writeText(checkNotNull(EngineFixture::class.java.getResource("/$name")).readText())
        check(setExecutable(true))
    }

    fun engine() = XrayBinary(binary.path, dir, port, transparent = true, owner = owner.path) {
        if (it != null) processes.add(it)
    }

    fun config(starting: Boolean = false) = buildJsonObject {
        put("port", port)
        put("started", started.path)
        put("starting", starting)
    }

    fun childAlive(): Boolean = ProcessHandle.of(started.readText().toLong()).map { it.isAlive }.orElse(false)

    fun close() {
        processes.forEach { process ->
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
        }
        dir.deleteRecursively()
    }
}

private suspend fun withEngineFixture(block: suspend (EngineFixture) -> Unit) {
    val fixture = EngineFixture(Files.createTempDirectory("xray-process-test").toFile())
    try {
        block(fixture)
    } finally {
        fixture.close()
    }
}
