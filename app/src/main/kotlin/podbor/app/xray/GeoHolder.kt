package podbor.app.xray

import android.util.Log
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import podbor.driver.EngineError
import podbor.driver.GeoCountry
import java.io.File

/**
 * geoip.dat читается один раз на процесс, вне главного потока, и переживает сессии VPN. Подъём ждёт загрузку ограниченно
 * ([await]): сбой или медленная загрузка дают null — страны «?», VPN не страдает. Сбой пишется в лог.
 */
object GeoHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loading: Deferred<GeoCountry?>? = null

    /** Запускает загрузку, если ещё не запущена; [file] вычисляется уже в фоне. */
    @Synchronized
    fun start(file: () -> File) {
        if (loading != null) return
        loading = scope.async {
            val result = Either.catch { GeoCountry.load(file()) }
                .fold({ Either.Left(EngineError.Failed("geoip.dat: ${it.message}")) }, { it })
            result.fold({ Log.w("Podbor", "страна по geoip недоступна: $it"); null }, { it })
        }
    }

    /** Загруженный geoip или null: не стартовал, не успел за [timeoutMs], не загрузился. */
    suspend fun await(timeoutMs: Long): GeoCountry? = withTimeoutOrNull(timeoutMs) { loading?.await() }
}
