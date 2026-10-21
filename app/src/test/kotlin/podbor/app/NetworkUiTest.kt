package podbor.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.NetworkScreen
import podbor.app.ui.PodborTheme
import podbor.core.NetworkCard
import podbor.core.OsNetwork

/** Ярус «СЕТЬ» сверху экрана. Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun versionInNetworkRow() {
        compose.setContent { PodborTheme { NetworkScreen(NetworkCard.Measuring(OsNetwork.Wifi), version = "1.2.3") } }
        compose.onNodeWithText("СЕТЬ").assertExists()
        compose.onNodeWithText("v1.2.3").assertExists()
    }
}
