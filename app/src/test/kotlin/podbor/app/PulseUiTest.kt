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
import podbor.app.ui.ServiceLine
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.screen.Mark
import podbor.screen.ServiceRow

/** Пульс на экране: трафик сессии в ярусе «СЕТЬ» и величины пары в строке сервиса. Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PulseUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun trafficNextToNetworkTier() {
        compose.setContent {
            PodborTheme { NetworkScreen(NetworkCard.Measuring(OsNetwork.Wifi), version = "1.2.3", traffic = "↓ 32,2 МБ · ↑ 192 КБ") }
        }
        compose.onNodeWithText("СЕТЬ").assertExists()
        compose.onNodeWithText("↓ 32,2 МБ · ↑ 192 КБ").assertExists()
        compose.onNodeWithText("v1.2.3").assertExists()
    }

    @Test
    fun noTrafficWithoutSession() {
        compose.setContent { PodborTheme { NetworkScreen(NetworkCard.Measuring(OsNetwork.Wifi), version = "1.2.3") } }
        compose.onNodeWithText("↓", substring = true).assertDoesNotExist()
    }

    @Test
    fun pulseUnderServiceRow() {
        compose.setContent {
            PodborTheme {
                ServiceLine(ServiceRow("TG", Mark.Works, "NL-7", "gRPC", emptyList(), searching = false, pulse = "↓ 340 КБ/с · ↑ 12 КБ/с · ответ 2 с назад"))
            }
        }
        compose.onNodeWithText("↓ 340 КБ/с · ↑ 12 КБ/с · ответ 2 с назад").assertExists()
    }
}
