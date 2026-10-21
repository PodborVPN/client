package podbor.app.xray

import android.util.Log
import arrow.core.Either
import arrow.core.flatMap
import arrow.fx.coroutines.ResourceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import podbor.driver.EngineError
import podbor.driver.XrayEngine

/**
 * Xray в процессе приложения через libXray. Запуск и остановка — один `install`: stopXray стоит в release
 * и срабатывает при выходе из области всегда — и когда runXray отказал (полузапущенное ядро гасится),
 * и на исключении или отмене в области.
 */
class LibXrayEngine(
    private val invoke: Invoke,
    /** Сбой остановки — в logcat (тег Podbor): только класс исключения или success=false, без текста ответа. */
    private val warn: (String) -> Unit = { Log.w("Podbor", it) },
) : XrayEngine {
    override suspend fun ResourceScope.run(config: JsonObject): Either<EngineError, Unit> =
        install({ start(config) }, { _, _ -> stop() })

    private suspend fun start(config: JsonObject): Either<EngineError, Unit> = withContext(Dispatchers.IO) {
        Either.catch { invoke.call(runRequest(config.toString())) }
            .mapLeft { EngineError.Failed("runXray бросил ${it.javaClass.simpleName}") }
            .flatMap(::parseRunResponse)
    }

    // Ответ stopXray некому вернуть (release не возвращает значение), а бросать нельзя — замаскирует причину выхода:
    // сбой остановки только записывается.
    private suspend fun stop() {
        withContext(Dispatchers.IO) {
            Either.catch { invoke.call(stopRequest()) }.fold(
                { warn("stopXray бросил ${it.javaClass.simpleName}") },
                { response -> parseRunResponse(response).onLeft { warn("stopXray: success=false") } },
            )
        }
    }
}
