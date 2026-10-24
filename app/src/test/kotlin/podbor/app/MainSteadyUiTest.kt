package podbor.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import podbor.app.ui.PodborTheme
import podbor.app.ui.ServiceLine
import podbor.screen.ServiceRow

/** Полоска поиска в строке сервиса появляется и уходит, не двигая то, что под строкой. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainSteadyUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun searchBarDoesNotShiftBelow() {
        var searching by mutableStateOf(false)
        compose.setContent {
            PodborTheme {
                Column {
                    ServiceLine(ServiceRow("IG", null, "подбираю выход…", null, emptyList(), searching = searching), onTap = {})
                    Text("ниже")
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val before = compose.onNodeWithText("ниже").getBoundsInRoot().top
        searching = true
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(before, compose.onNodeWithText("ниже").getBoundsInRoot().top)
        searching = false
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(before, compose.onNodeWithText("ниже").getBoundsInRoot().top)
    }
}
