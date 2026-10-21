package podbor.app.xray

import android.content.Context
import android.util.Log
import arrow.core.Either
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import podbor.core.WhiteLists
import java.io.File

private val GEO_FILES = listOf("geoip.dat", "geosite.dat")

/**
 * Geo-файлы из assets → filesDir/xray: Xray читает их с диска (env xray.location.asset). Копируются только после
 * установки/обновления APK или если на диске не то, что записали ([geoFresh]); иначе — готовые (быстрее подъём).
 */
fun geoDir(context: Context): File = synchronized(GEO_LOCK) { geoDirLocked(context) }

/** Подъём VPN и фоновая загрузка страны зовут geoDir одновременно — копирование assets не должно идти двумя потоками. */
private val GEO_LOCK = Any()

private fun geoDirLocked(context: Context): File {
    val dir = File(context.filesDir, "xray").also { it.mkdirs() }
    val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    val markerFile = File(dir, "installed.marker")
    val onDisk = GEO_FILES.associateWith { name -> File(dir, name).takeIf { it.isFile }?.length() }
    val marker = markerFile.takeIf { it.isFile }?.readText()
    if (!geoFresh(marker, stamp, onDisk)) {
        // Метку — сначала долой: прерванное копирование не оставит «свежую» метку при недописанных файлах.
        markerFile.delete()
        for (name in GEO_FILES) {
            context.assets.open("xray/$name").use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        markerFile.writeText(geoMarker(stamp, GEO_FILES.associateWith { File(dir, it).length() }))
    }
    return dir
}

/** Списки белых IP/SNI из assets (whitelist/ip.txt, sni.txt); сборка кладёт их по sha256-пину. */
fun whiteListsOf(context: Context): WhiteLists {
    fun lines(name: String) = context.assets.open("whitelist/$name").use { it.readBytes().decodeToString() }.lines()
    return WhiteLists.of(lines("ip.txt"), lines("sni.txt"))
}

/**
 * Списки читаются один раз на процесс, вне главного потока (как [GeoHolder]); ~30 тыс. строк разбираются заметно.
 * Сбой пишется в лог, [await] даёт null.
 */
object WhiteHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loading: Deferred<WhiteLists?>? = null

    @Synchronized
    fun start(context: Context) {
        if (loading != null) return
        val app = context.applicationContext
        loading = scope.async {
            Either.catch { whiteListsOf(app) }
                .onLeft { Log.w("Podbor", "белые списки недоступны: ${it.message}") }
                .getOrNull()
        }
    }

    suspend fun await(timeoutMs: Long): WhiteLists? = withTimeoutOrNull(timeoutMs) { loading?.await() }
}
