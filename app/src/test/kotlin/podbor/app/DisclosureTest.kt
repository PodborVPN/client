package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.ui.permissionsFor

/** Что просить у системы после шторки: геолокацию — только с согласия; уведомления — на Android 13+ в любом случае. */
class DisclosureTest : FunSpec({
    val location = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
    val notifications = listOf("android.permission.POST_NOTIFICATIONS")

    test("согласие на Android 13+ — геолокация и уведомления") {
        permissionsFor(locationAllowed = true, sdk = 33) shouldBe location + notifications
    }
    test("согласие до Android 13 — только геолокация") {
        permissionsFor(locationAllowed = true, sdk = 32) shouldBe location
    }
    test("«не сейчас» — геолокацию не просим") {
        permissionsFor(locationAllowed = false, sdk = 33) shouldBe notifications
        permissionsFor(locationAllowed = false, sdk = 26) shouldBe emptyList()
    }
})
