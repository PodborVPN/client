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
import podbor.app.ui.NetworkScreen
import podbor.app.ui.PodborTheme
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.Scenario

/** Плашка сети не меняет высоту, когда замер кончается: страница под ней (кнопка) стоит. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkSteadyUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun steady(measuring: NetworkCard, measured: NetworkCard) {
        var card by mutableStateOf(measuring)
        compose.setContent {
            PodborTheme {
                Column {
                    NetworkScreen(card, version = "1")
                    Text("ниже")
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithText("ниже").getBoundsInRoot().top
        card = measured
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithText("ниже").getBoundsInRoot().top)
    }

    @Test
    fun wifi() = steady(NetworkCard.Measuring(OsNetwork.Wifi), NetworkCard.Measured(OsNetwork.Wifi, Scenario.Wifi, "заграница отвечает"))

    @Test
    fun cellular() {
        val mts = OsNetwork.Cellular("MTS", "25001")
        steady(NetworkCard.Measuring(mts), NetworkCard.Measured(mts, Scenario.MobileWhitelist, "заграница не отвечает, РФ работает"))
    }

    @Test
    fun noNetwork() = steady(NetworkCard.Measuring(OsNetwork.None), NetworkCard.Measured(OsNetwork.None, Scenario.Offline, "ничего не отвечает"))
}
