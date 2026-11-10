package podbor.linux

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.ResourceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import podbor.driver.EngineError
import podbor.driver.XrayEngine
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

private const val READY_MS = 8_000L
private const val STOP_SEC = 3L

/**
 * Xray отдельным процессом: один запуск — один процесс, готовность — открытый порт API. Конфиг в [workDir] с правами
 * 600: в нём ключи серверов подписки.
 */
class XrayBinary(
    private val binary: String,
    private val workDir: File,
    private val apiPort: Int,
) : XrayEngine {
    override suspend fun ResourceScope.run(config: JsonObject): Either<EngineError, Unit> {
        var process: Process? = null
        return install(
            { withContext(Dispatchers.IO) { start(config) { process = it } } },
            { _, _ -> withContext(Dispatchers.IO) { listOfNotNull(process).forEach(::stop) } },
        )
    }

    private suspend fun start(config: JsonObject, started: (Process) -> Unit): Either<EngineError, Unit> {
        if (!waitPort(open = false)) return EngineError.Failed("порт API :$apiPort занят").left()
        val file = File(workDir, "xray.json")
        writePrivate(file, config.toString())
        val p = Either.catch {
            ProcessBuilder(binary, "run", "-c", file.path)
                .redirectErrorStream(true)
                .redirectOutput(File(workDir, "xray.log"))
                .start()
        }.fold({ return EngineError.Failed("Xray не запустился: ${it.javaClass.simpleName}").left() }, { it })
        started(p)
        val deadline = System.currentTimeMillis() + READY_MS
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return EngineError.Failed("Xray вышел с кодом ${p.exitValue()}").left()
            if (portOpen()) return Unit.right()
            delay(100)
        }
        return EngineError.Failed("Xray не поднял API :$apiPort").left()
    }

    private fun stop(p: Process) {
        p.destroy()
        if (!p.waitFor(STOP_SEC, TimeUnit.SECONDS)) p.destroyForcibly().waitFor()
    }

    private suspend fun waitPort(open: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + READY_MS
        while (System.currentTimeMillis() < deadline) {
            if (portOpen() == open) return true
            delay(100)
        }
        return false
    }

    private fun portOpen(): Boolean =
        Either.catch { Socket().use { it.connect(InetSocketAddress("127.0.0.1", apiPort), 200) } }.isRight()
}

/** Файл с правами 600: создаётся с ними сразу, а не правится после записи. */
internal fun writePrivate(file: File, text: String) {
    file.absoluteFile.parentFile.mkdirs()
    file.delete()
    Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    file.writeText(text)
}
