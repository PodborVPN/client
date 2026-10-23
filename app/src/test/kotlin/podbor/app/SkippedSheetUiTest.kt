package podbor.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.PodborTheme
import podbor.app.ui.SkippedSheet
import podbor.screen.Skipped

/** Шторка о ссылках подписки, не ставших серверами. Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkippedSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var dismissed = 0

    private fun show(skipped: Skipped) = compose.setContent {
        PodborTheme { SkippedSheet(skipped, onDismiss = { dismissed++ }) }
    }

    @Test
    fun partOfSubscriptionSkippedShowsCountsAndGoesOn() {
        show(Skipped(4, mapOf("hysteria2" to 3), broken = 1))
        compose.onNodeWithText("Проверю 4 сервера из 8").assertExists()
        compose.onNodeWithText("hysteria2 — 3").assertExists()
        compose.onNodeWithText("битая ссылка — 1").assertExists()
        compose.onNodeWithText("остальные Podbor не поддерживает — пропущу").assertExists()
        compose.onNodeWithText("Понятно").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun nothingSupportedSaysWhatToAskFor() {
        show(Skipped(0, mapOf("tuic" to 2), broken = 0))
        compose.onNodeWithText("Podbor не поддерживает эту подписку").assertExists()
        compose.onNodeWithText("tuic — 2").assertExists()
        compose.onNodeWithText("Podbor понимает vless, vmess, trojan и shadowsocks — попроси у провайдера подписку на них").assertExists()
    }

    @Test
    fun unknownFormatHasNoCounts() {
        show(Skipped(0, emptyMap(), broken = 0))
        compose.onNodeWithText("Не узнал формат подписки").assertExists()
        compose.onNodeWithText("Понятно").performClick()
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }
}
