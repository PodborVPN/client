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
import podbor.app.ui.LocationSheet
import podbor.app.ui.PodborTheme

/** Шторка перед запросом геолокации: что берём, зачем, что не берём, куда уходит; «Разрешить» и «Не сейчас». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisclosureUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show() = compose.setContent {
        PodborTheme { LocationSheet(onAllow = { calls += "allow" }, onDecline = { calls += "decline" }) }
    }

    @Test
    fun saysWhatWhyAndWhere() {
        show()
        compose.onNodeWithText("Зачем Podbor геолокация").assertExists()
        compose.onNodeWithText("Номер вышки", substring = true).assertExists()
        compose.onNodeWithText("GPS", substring = true).assertExists()
        compose.onNodeWithText("никуда не отправляется", substring = true).assertExists()
    }

    @Test
    fun allow() {
        show()
        compose.onNodeWithText("Разрешить").performClick()
        compose.waitForIdle()
        assertEquals(listOf("allow"), calls)
    }

    @Test
    fun decline() {
        show()
        compose.onNodeWithText("Не сейчас").performClick()
        compose.waitForIdle()
        assertEquals(listOf("decline"), calls)
    }
}
