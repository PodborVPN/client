package podbor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.DpRect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import podbor.app.ui.PodborTheme
import podbor.app.ui.ScanScreen
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.Mark3
import podbor.core.ScanBoard
import podbor.core.ServerCard
import podbor.core.ServiceId
import podbor.core.ServiceTally
import podbor.core.Transport
import podbor.core.WhiteHint

/** Экран анализа не трясёт: числа в фишках и отметки на карточках меняются, соседи стоят. Настоящая вёрстка текста. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanSteadyUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun card(tg: Mark3, ig: Mark3) = ServerCard(
        key = "k1", entry = Entry.Ru, exit = Exit.Unknown, whiteHint = WhiteHint.Likely, sniWhite = false,
        transport = Transport.Xhttp, services = listOf("TG" to tg, "IG" to ig), live = true,
    )

    private fun board(tg: Int, ig: Int, card: ServerCard) = ScanBoard(
        total = 92, done = 3, ruEntries = 21, likelyWhite = 10,
        tallies = listOf(ServiceTally(ServiceId("tg"), "TG", tg), ServiceTally(ServiceId("ig"), "IG", ig)),
        cards = listOf(card), verdict = emptyList(), finished = false,
    )

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun bounds(text: String): DpRect = compose.onNodeWithText(text, substring = true).getBoundsInRoot()

    /** Отметка сервиса на карточке — по её подписи для озвучки. */
    private fun marked(words: String): DpRect = compose.onNodeWithContentDescription(words).getBoundsInRoot()

    @Test
    fun growingTalliesKeepChipsAndFeedInPlace() {
        var b by mutableStateOf(board(0, 0, card(Mark3.Pending, Mark3.Pending)))
        compose.setContent { PodborTheme { ScanScreen(b, onOk = {}) } }
        settle()
        val ig = bounds("IG ✓ ")
        val route = bounds("Россия")
        b = board(47, 35, card(Mark3.Pending, Mark3.Pending))
        settle()
        assertEquals(ig.left, bounds("IG ✓ ").left)
        assertEquals(ig.top, bounds("IG ✓ ").top)
        assertEquals(route.top, bounds("Россия").top)
    }

    @Test
    fun markChangeKeepsNeighbourMark() {
        var b by mutableStateOf(board(0, 0, card(Mark3.Pending, Mark3.Pending)))
        compose.setContent { PodborTheme { ScanScreen(b, onOk = {}) } }
        settle()
        val ig = marked("IG …")
        b = board(1, 0, card(Mark3.Works, Mark3.Pending))
        settle()
        assertEquals(ig.left, marked("IG …").left)
        b = board(1, 0, card(Mark3.Dead, Mark3.Pending))
        settle()
        assertEquals(ig.left, marked("IG …").left)
    }
}
