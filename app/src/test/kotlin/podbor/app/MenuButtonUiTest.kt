package podbor.app

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.MENU_LABEL
import podbor.app.ui.MainScreen
import podbor.app.ui.PodborTheme
import podbor.core.HARDCODED_SERVICES
import podbor.core.board
import podbor.screen.Link

/** Кнопка «⋮» — кнопка, а не значок: касание не меньше 48 dp, и она открывает меню. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MenuButtonUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bigEnoughAndOpensMenu() {
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board(HARDCODED_SERVICES, emptyMap()), Link.Off, emptyMap(), null, null,
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {},
                )
            }
        }
        val button = compose.onNode(hasClickAction() and hasContentDescription(MENU_LABEL), useUnmergedTree = false)
        val b = button.getBoundsInRoot()
        assert(b.bottom - b.top >= 48.dp && b.right - b.left >= 48.dp) { "касание кнопки меню меньше 48 dp: $b" }
        button.performClick()
        compose.onNodeWithText("Сменить подписку").assertExists()
    }
}
