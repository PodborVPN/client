package podbor.driver

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/** LinkConverter для JVM: docker-образ `podbor-libxray` с libXray — тот же Invoke, что в AAR. */
class LibXrayCli(
    private val command: List<String> = listOf("docker", "run", "--rm", "-i", "podbor-libxray"),
    private val timeoutSec: Long = 30,
) : LinkConverter {
    override suspend fun convert(text: String): Either<EngineError, List<JsonObject>> = withContext(Dispatchers.IO) {
        val request = buildJsonObject {
            put("apiVersion", 3)
            put("method", "convertShareLinksToXrayJson")
            putJsonObject("payload") { put("text", text) }
        }
        resourceScope {
            either {
                // Вывод — в файл: чтение до EOF раньше waitFor вешало нажатие, если конвертер завис.
                val out = install({ File.createTempFile("libxray", ".out") }, { f, _ -> f.delete() })
                val process = Either.catch {
                    install({ ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out).start() }, { p, _ -> p.destroyForcibly() })
                }.mapLeft { EngineError.Failed("конвертер не запустился: ${it.message}") }.bind()
                // Запись не удалась (процесс уже умер) — ответ всё равно читаем из файла, он скажет почему.
                Either.catch { process.outputStream.use { it.write(request.toString().toByteArray()) } }
                ensure(runInterruptible { process.waitFor(timeoutSec, TimeUnit.SECONDS) }) {
                    EngineError.Failed("конвертер не ответил за $timeoutSec с")
                }
                outboundsOfResponse(out.readText()).bind()
            }
        }
    }
}
