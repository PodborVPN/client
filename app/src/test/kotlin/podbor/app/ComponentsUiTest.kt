package podbor.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.IconSquareButton
import podbor.app.ui.PodborTheme
import podbor.app.ui.PrimaryButton
import podbor.app.ui.ServiceLine
import podbor.app.ui.Space
import podbor.core.Telegram
import podbor.screen.Mark
import podbor.screen.ServiceRow

/** Компоненты правил UI: капс — только на экране, касание кнопки-значка — 48dp и в строке 40dp, знак сервиса в строке. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComponentsUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryShowsCapsButSpeaksWords() {
        var taps = 0
        compose.setContent { PodborTheme { PrimaryButton("Понятно", { taps++ }) } }
        // Для озвучки и поиска — слова как написаны; на экране — капсом.
        compose.onNodeWithText("Понятно").performClick()
        assertEquals(1, taps)
        compose.onNodeWithText("ПОНЯТНО").assertDoesNotExist()
    }

    @Test
    fun iconButtonTouchIs48EvenInA40Row() {
        compose.setContent {
            PodborTheme { Row(Modifier.height(Space.control)) { IconSquareButton(R.drawable.ic_wrench, "ключ", {}) } }
        }
        val b = compose.onNode(hasClickAction() and hasContentDescription("ключ")).getBoundsInRoot()
        assert(b.bottom - b.top >= 48.dp && b.right - b.left >= 48.dp) { "касание меньше 48 dp: $b" }
    }

    @Test
    fun serviceLineShowsServiceSign() {
        compose.setContent {
            PodborTheme {
                ServiceLine(ServiceRow("TG", Mark.Works, "NL-7", "gRPC", emptyList(), searching = false, service = Telegram.id), onTap = {})
            }
        }
        // Знак сервиса подписан его меткой; метки словом рядом нет.
        compose.onNodeWithContentDescription("TG").assertExists()
        compose.onNodeWithText("TG").assertDoesNotExist()
    }

    @Test
    fun unknownServiceKeepsItsLabel() {
        compose.setContent {
            PodborTheme {
                ServiceLine(ServiceRow("YT", Mark.Checking, "NL-7", null, emptyList(), searching = false), onTap = {})
            }
        }
        compose.onNodeWithText("YT").assertExists()
    }
}
