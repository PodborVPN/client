package podbor.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import podbor.app.sub.SubscriptionNote
import podbor.app.ui.Link
import podbor.app.ui.MainScreen
import podbor.app.ui.PRESS_TAG
import podbor.app.ui.PodborTheme
import podbor.app.ui.SHARE_TAG
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.Instagram
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.ServiceCard
import podbor.core.Telegram

/** Главная — один экран: кнопка подбора сверху, Лента и «Поддержать» прижаты к низу, без прокрутки страницы. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OneScreenUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var board by mutableStateOf(board(CardStatus.Unknown, CardStatus.Unknown))
    private var announce by mutableStateOf<String?>(null)

    private fun show() = compose.setContent {
        PodborTheme {
            MainScreen(
                board, Link.Up, emptyMap(), null, SubscriptionNote(null, emptyList(), null, announce),
                onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {}, onDonate = {},
            )
        }
    }

    private fun board(tg: CardStatus, ig: CardStatus) =
        Board(NetworkCard.Measuring(OsNetwork.Wifi), Button.Ready, listOf(ServiceCard(Telegram.id, "TG", tg), ServiceCard(Instagram.id, "IG", ig)))

    @Test
    fun bottomLineStaysPutWhateverTheCardsAndFitsOnScreen() {
        show()
        val short = compose.onNodeWithTag(SHARE_TAG).getBoundsInRoot()
        val press = compose.onNodeWithTag(PRESS_TAG).getBoundsInRoot()
        val lines = (1..12).map { "строка объяснения номер $it, достаточно длинная, чтобы переноситься" }
        board = board(CardStatus.NotFound(lines), CardStatus.NotFound(lines))
        announce = (1..10).joinToString(" ") { "объявление провайдера" }
        compose.waitForIdle()
        // Длинное — прокручивается середина, а не страница: кнопка подбора и нижняя линия на тех же местах и видны.
        assertEquals(short, compose.onNodeWithTag(SHARE_TAG).getBoundsInRoot())
        assertEquals(press, compose.onNodeWithTag(PRESS_TAG).getBoundsInRoot())
        compose.onNodeWithTag(PRESS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(SHARE_TAG).assertIsDisplayed()
        assert(short.bottom <= compose.onRoot().getBoundsInRoot().bottom)
    }

    @Test
    fun longAnnounceIsTwoLinesAtMost() {
        announce = (1..30).joinToString(" ") { "слово" }
        show()
        val one = compose.onNodeWithText("слово", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].size
        assertEquals(1, one)
        val height = compose.onNodeWithText("слово", substring = true).getBoundsInRoot().let { it.bottom - it.top }
        // bodyMedium: строка 14 sp + 6 — две строки ≈ 40 dp.
        assert(height <= 44.dp) { "объявление выше двух строк: $height" }
    }
}
