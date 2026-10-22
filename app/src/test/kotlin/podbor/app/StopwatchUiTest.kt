package podbor.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.MainScreen
import podbor.app.ui.LocalNowMs
import podbor.app.ui.PodborTheme
import podbor.app.ui.PressButton
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.Instagram
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.ServiceCard
import podbor.core.Stopwatch
import podbor.core.Telegram
import podbor.core.Transport
import podbor.screen.Link
import podbor.screen.Face

/** Секундомер на кнопке: тикает по часам отрисовки, замирает на итоге. Часы — поддельные. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StopwatchUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var now = 0L

    private fun show(face: Face) = compose.setContent {
        CompositionLocalProvider(LocalNowMs provides { now }) {
            PodborTheme { PressButton(face, onPress = {}, onOff = {}, onOn = {}) }
        }
    }

    @Test
    fun runningTicks() {
        now = 1_000
        show(Face.Press(Button.Searching, Stopwatch.Running(startedAtMs = 1_000)))
        compose.onNodeWithText("0:00").assertIsDisplayed()
        now = 8_500
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("0:07").assertIsDisplayed()
    }

    @Test
    fun stoppedHoldsResult() {
        now = 99_000
        show(Face.Press(Button.Green, Stopwatch.Stopped(1_000, 9_200)))
        compose.onNodeWithText("0:08").assertIsDisplayed()
        compose.onNodeWithText("РАБОТАЕТ").assertIsDisplayed()
    }

    @Test
    fun noStopwatchNoTime() {
        show(Face.Press(Button.Ready))
        compose.onNodeWithText("0:00").assertDoesNotExist()
    }

    @Test
    fun cardsShowOwnTimeButtonShowsOverall() {
        now = 4_000
        val board = Board(
            NetworkCard.Measuring(OsNetwork.Wifi), Button.Searching,
            listOf(
                ServiceCard(Telegram.id, "TG", CardStatus.Found(ExitRef("NL-7", Transport.Grpc), 2_300, ""), Stopwatch.Stopped(1_000, 2_300)),
                ServiceCard(Instagram.id, "IG", CardStatus.Searching, Stopwatch.Running(1_000)),
            ),
            Stopwatch.Running(1_000),
        )
        compose.setContent {
            CompositionLocalProvider(LocalNowMs provides { now }) {
                PodborTheme {
                    MainScreen(board, Link.Up, emptyMap(), null, null, onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {})
                }
            }
        }
        // Экран Robolectric низкий: карточки под кнопкой — докрутить.
        compose.onNodeWithText("1,3 с").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3,0 с").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0:03").assertIsDisplayed()
    }
}
