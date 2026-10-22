package podbor.app

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assert
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.Link
import podbor.app.ui.MainScreen
import podbor.app.ui.PodborTheme
import podbor.app.ui.SHARE_TAG
import podbor.core.HARDCODED_SERVICES
import podbor.core.board

/** «поделиться Лентой» — видно, что кнопка: роль, палец помещается, нажатие отдаёт Ленту. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShareButtonUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shareIsAButton() {
        var shared = 0
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board(HARDCODED_SERVICES, emptyMap()), Link.Up, emptyMap(), null, null,
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = { shared++ }, onChangeSubscription = {},
                )
            }
        }
        compose.onNodeWithTag(SHARE_TAG)
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, shared)
    }
}
