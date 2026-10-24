package podbor.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.MainScreen
import podbor.app.ui.PodborTheme
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.ServiceCard
import podbor.core.Telegram
import podbor.screen.Announce
import podbor.screen.Link
import podbor.screen.SubscriptionNote
import podbor.screen.Support

/** Ничего не работает — на главном, под словами тупика, кнопка поддержки провайдера; только если он дал ссылку. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StuckSupportUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(button: Button, support: Support) = compose.setContent {
        val card = when (button) {
            Button.Red -> CardStatus.NotFound(listOf("Белые списки."))
            Button.Idle, Button.Ready, Button.Searching, Button.Green -> CardStatus.Unknown
        }
        PodborTheme {
            MainScreen(
                Board(NetworkCard.Measuring(OsNetwork.Wifi), button, listOf(ServiceCard(Telegram.id, "TG", card))),
                Link.Up, emptyMap(), null, SubscriptionNote(null, emptyList(), support, Announce.None),
                onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {},
                onSupport = { calls += it },
            )
        }
    }

    @Test
    fun stuckWithLinkShowsSupportAndOpensIt() {
        show(Button.Red, Support.Link("https://t.me/help"))
        compose.onNodeWithText("Написать в поддержку провайдера").performScrollTo().performClick()
        assertEquals(listOf("https://t.me/help"), calls)
    }

    @Test
    fun stuckWithoutLinkNoButton() {
        show(Button.Red, Support.None)
        compose.onNodeWithText("Написать в поддержку провайдера").assertDoesNotExist()
    }

    @Test
    fun workingNoButton() {
        show(Button.Green, Support.Link("https://t.me/help"))
        compose.onNodeWithText("Написать в поддержку провайдера").assertDoesNotExist()
    }
}
