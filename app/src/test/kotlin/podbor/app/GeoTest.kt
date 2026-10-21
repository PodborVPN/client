package podbor.app

import io.kotest.core.spec.style.FunSpec
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
    test("метки нет (первый запуск) — копировать") {
        geoFresh(null, stamp = 42, onDisk = sizes) shouldBe false
    }
    test("APK обновлён (другая метка установки) — копировать") {
        geoFresh(marker, stamp = 43, onDisk = sizes) shouldBe false
    }
    test("файла нет или размер не тот (недокопирован, удалён) — копировать") {
        geoFresh(marker, stamp = 42, onDisk = mapOf("geoip.dat" to 100L, "geosite.dat" to null)) shouldBe false
        geoFresh(marker, stamp = 42, onDisk = mapOf("geoip.dat" to 100L, "geosite.dat" to 199L)) shouldBe false
    }
    test("испорченная метка — копировать") {
        geoFresh("мусор", stamp = 42, onDisk = sizes) shouldBe false
    }
})
