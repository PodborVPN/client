package podbor.app

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.MainScreen
import podbor.app.ui.PodborTheme
import podbor.core.HARDCODED_SERVICES
import podbor.core.board
import podbor.screen.Link

/** Кнопка «⋮» — кнопка, а не значок: в неё попадаешь пальцем (не меньше 40 dp), и она открывает меню. */
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
        val button = compose.onNode(hasClickAction() and hasText("⋮"), useUnmergedTree = false)
        val b = button.getBoundsInRoot()
        assert(b.bottom - b.top >= 40.dp && b.right - b.left >= 40.dp) { "кнопка меню меньше 40 dp: $b" }
        button.performClick()
        compose.onNodeWithText("Сменить подписку").assertExists()
    }
}
