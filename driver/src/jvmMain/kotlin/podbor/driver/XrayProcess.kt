package podbor.driver

import arrow.core.Either
import arrow.core.getOrElse
import arrow.fx.coroutines.ResourceScope
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * XrayEngine для JVM: Xray живёт в docker-контейнере (бинарь v26.10.17), конфиг — через смонтированный каталог
 * [hostDir]. JVM запущена в netns того же контейнера, поэтому api-порт виден на 127.0.0.1.
 */
class XrayProcess(
    private val container: String = "podbor-xray",
    private val hostDir: File,
    private val containerDir: String = "/xray",
    private val apiPort: Int = 10085,
    private val readyTimeoutMs: Long = 5_000,
    private val dockerBin: String = "docker",
    private val commandTimeoutSec: Long = 10,
) : XrayEngine {
    private val name = "press-xray.json"

    /** Шаблон pkill, который не совпадает с собственной командой sh -c (иначе pkill убивает сам себя). */
    private val killPattern = "[${name.first()}]${name.drop(1)}"

    override suspend fun ResourceScope.run(config: JsonObject): Either<EngineError, Unit> =
        // Остановка — release: срабатывает и когда запуск не удался на середине, и на исключении, и на отмене.
        install({ start(config) }, { _, _ -> kill() })

    private suspend fun start(config: JsonObject): Either<EngineError, Unit> = withContext(Dispatchers.IO) {
        File(hostDir, name).writeText(config.toString())
        if (!docker("exec", container, "sh", "-c", "pkill -f '$killPattern'; true")) return@withContext failed("docker не ответил")
        // Готовность по порту честна, только если старый Xray его уже отпустил.
        if (!waitPort(open = false)) return@withContext failed("старый Xray держит :$apiPort")
        if (!docker("exec", "-d", container, "sh", "-c", "xray run -c $containerDir/$name >/tmp/press-xray.log 2>&1")) {
            return@withContext failed("docker не запустил Xray")
        }
        if (waitPort(open = true)) Unit.right() else failed("Xray не поднял api :$apiPort (лог: $container:/tmp/press-xray.log)")
    }

    private suspend fun kill() {
        withContext(Dispatchers.IO) { docker("exec", container, "sh", "-c", "pkill -f '$killPattern'; true") }
    }

    private suspend fun waitPort(open: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val isOpen = Either.catch { Socket().use { it.connect(InetSocketAddress("127.0.0.1", apiPort), 200) } }.isRight()
            if (isOpen == open) return true
            delay(100)
        }
        return false
    }

    /** true — команда завершилась за срок; зависший или отсутствующий docker — false, не исключение. */
    private fun docker(vararg args: String): Boolean = Either.catch {
        val p = ProcessBuilder(dockerBin, *args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        p.waitFor(commandTimeoutSec, TimeUnit.SECONDS).also { if (!it) p.destroyForcibly() }
    }.getOrElse { false }

    private fun failed(what: String): Either<EngineError, Unit> = EngineError.Failed(what).left()
}
