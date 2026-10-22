package podbor.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import podbor.app.ui.NetworkScreen
import podbor.app.ui.PodborTheme
import podbor.app.update.Offer
import podbor.core.NetworkCard
import podbor.core.OsNetwork

/** Есть новее на GitHub — вместо версии янтарный чип-кнопка с новой версией; строка сети от этого не выше. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val card = NetworkCard.Measuring(OsNetwork.Wifi)

    private fun show(offer: Offer?, opened: MutableList<String> = mutableListOf()) = compose.setContent {
        PodborTheme {
            Column {
                NetworkScreen(card, version = "0.8.0", update = offer, onUpdate = { opened += it })
                Text("ниже")
            }
        }
    }

    @Test
    fun noOfferShowsVersion() {
        show(null)
        compose.onNodeWithText("v0.8.0").assertExists()
    }

    @Test
    fun offerIsAButtonWithNewVersionAndOpensLink() {
        val opened = mutableListOf<String>()
        show(Offer("0.10.0", "https://example.org/podbor-0.10.0.apk"), opened)
        compose.onNodeWithText("v0.8.0").assertDoesNotExist()
        compose.onNodeWithText("0.10.0", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(listOf("https://example.org/podbor-0.10.0.apk"), opened)
    }

    @Test
    fun chipDoesNotGrowNetworkRow() {
        var offer by mutableStateOf<Offer?>(null)
        compose.setContent {
            PodborTheme {
                Column {
                    NetworkScreen(card, version = "0.8.0", update = offer, onUpdate = {})
                    Text("ниже")
                }
            }
        }
        val plain = compose.onNodeWithText("ниже").getBoundsInRoot().top
        offer = Offer("0.10.0", "https://example.org/a.apk")
        compose.waitForIdle()
        compose.onNodeWithText("0.10.0", substring = true).assertExists()
        assertEquals(plain, compose.onNodeWithText("ниже").getBoundsInRoot().top)
    }
}
