package podbor.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.AlwaysOnSheet
import podbor.app.ui.PodborTheme

/** Шторка «постоянный VPN»: зачем, что включить и чем это обходится; «Открыть настройки» и «Не сейчас». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlwaysOnSheetUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show() = compose.setContent {
        PodborTheme { AlwaysOnSheet(onOpen = { calls += "open" }, onLater = { calls += "later" }) }
    }

    @Test
    fun saysWhyWhatAndThePrice() {
        show()
        compose.onNodeWithText("Чтобы VPN не выключался").assertExists()
        compose.onNodeWithText("«Постоянная VPN»", substring = true).assertExists()
        compose.onNodeWithText("«Блокировать соединения без VPN»", substring = true).assertExists()
        compose.onNodeWithText("интернета не будет", substring = true).assertExists()
    }

    @Test
    fun open() {
        show()
        compose.onNodeWithText("Открыть настройки").performClick()
        compose.waitForIdle()
        assertEquals(listOf("open"), calls)
    }

    @Test
    fun later() {
        show()
        compose.onNodeWithText("Не сейчас").performClick()
        compose.waitForIdle()
        assertEquals(listOf("later"), calls)
    }
}
