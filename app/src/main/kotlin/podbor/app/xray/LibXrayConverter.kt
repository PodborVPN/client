package podbor.app.xray

import arrow.core.Either
import arrow.core.flatMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import podbor.driver.EngineError
import podbor.driver.LinkConverter

/** Подписка → outbound'ы Xray конвертером libXray в процессе (convertShareLinksToXrayJson). */
class LibXrayConverter(private val invoke: Invoke) : LinkConverter {
    override suspend fun convert(text: String): Either<EngineError, List<JsonObject>> = withContext(Dispatchers.IO) {
        Either.catch { invoke.call(convertRequest(text)) }
            .mapLeft { EngineError.Failed("конвертер бросил ${it.javaClass.simpleName}") }
            .flatMap(::parseConvertResponse)
    }
}
