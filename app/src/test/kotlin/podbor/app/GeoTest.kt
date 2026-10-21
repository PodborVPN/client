package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import podbor.app.xray.geoFresh
import podbor.app.xray.geoMarker

/** Geo-файлы копируются из APK только после установки/обновления или если на диске не то, что записали. */
class GeoTest : FunSpec({
    val sizes = mapOf("geoip.dat" to 100L, "geosite.dat" to 200L)
    val marker = geoMarker(stamp = 42, sizes = sizes)

    test("та же установка и файлы те же — не копировать") {
        geoFresh(marker, stamp = 42, onDisk = sizes) shouldBe true
    }

    /** Случай, когда geo-файлы надо копировать заново. */
    data class Stale(val name: String, val marker: String?, val stamp: Long, val onDisk: Map<String, Long?>)

    context("копировать") {
        withData(
            nameFn = { it.name },
            Stale("метки нет (первый запуск)", null, 42, sizes),
            Stale("APK обновлён (другая метка установки)", marker, 43, sizes),
            Stale("файла нет (удалён)", marker, 42, mapOf("geoip.dat" to 100L, "geosite.dat" to null)),
            Stale("размер не тот (недокопирован)", marker, 42, mapOf("geoip.dat" to 100L, "geosite.dat" to 199L)),
            Stale("испорченная метка", "мусор", 42, sizes),
        ) { geoFresh(it.marker, stamp = it.stamp, onDisk = it.onDisk) shouldBe false }
    }
})
