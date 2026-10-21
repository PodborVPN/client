package podbor.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.FEED_CARDS
import podbor.app.ui.PodborTheme
import podbor.app.ui.SKIP_WARNING
import podbor.app.ui.ScanScreen
import podbor.app.ui.flagOf
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.Mark3
import podbor.core.ScanBoard
import podbor.core.ServerCard
import podbor.core.ServiceId
import podbor.core.ServiceTally
import podbor.core.Transport
import podbor.core.WhiteHint

/** Три сервиса, один выдуманный («YT»): экран не знает сервисов по именам — фишки и отметки от доски. */
private val tallies = listOf(
    ServiceTally(ServiceId("tg"), "TG", 5),
    ServiceTally(ServiceId("ig"), "IG", 2),
    ServiceTally(ServiceId("yt"), "YT", 1),
)

private val card = ServerCard(
    key = "k1",
    entry = Entry.Ru,
    exit = Exit.At("198.51.100.1", "NL"),
    whiteHint = WhiteHint.Likely,
    sniWhite = false,
    transport = Transport.Xhttp,
    services = listOf("TG" to Mark3.Works, "IG" to Mark3.Dead, "YT" to Mark3.Pending),
    live = true,
)

private fun board(finished: Boolean) = ScanBoard(
    total = 35, done = 7, ruEntries = 4, likelyWhite = 2, tallies = tallies, cards = listOf(card),
    verdict = if (finished) listOf("Обычная сеть: TG ✓ 5 · IG ✓ 2 · YT ✓ 1", "Белые списки: вероятно 2 сервера") else emptyList(),
    finished = finished,
)

/** Экран анализа: сводка, лента карточек, итог и «ОК». Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanUiTest {

    @get:Rule
    val compose = createComposeRule()

    private var oks = 0

    private fun show(board: ScanBoard?) = compose.setContent { PodborTheme { ScanScreen(board, onOk = { oks++ }) } }

    @Test
    fun threeServicesThreeChipsThreeMarks() {
        show(board(finished = false))
        compose.onNodeWithText("АНАЛИЗИРУЮ ПОДПИСКУ").assertExists()
        compose.onNodeWithText("7 / 35").assertExists()
        compose.onNodeWithText("35 серверов").assertExists()
        compose.onNodeWithText("4 входа в РФ").assertExists()
        compose.onNodeWithText("2 вероятно для белых списков").assertExists()
        compose.onNodeWithText("TG ✓ 5").assertExists()
        compose.onNodeWithText("IG ✓ 2").assertExists()
        compose.onNodeWithText("YT ✓ 1").assertExists()
        compose.onNodeWithText("TG ✓").assertExists()
        compose.onNodeWithText("IG ✗").assertExists()
        compose.onNodeWithText("YT …").assertExists()
    }

    @Test
    fun cardShowsRouteHintAndTransport() {
        show(board(finished = false))
        compose.onNodeWithText("${flagOf("RU")} Россия → ${flagOf("NL")} Нидерланды").assertExists()
        compose.onNodeWithText("белые списки: вероятно").assertExists()
        compose.onNodeWithText("XHTTP").assertExists()
    }

    @Test
    fun okEarlyIsOkNext() {
        show(board(finished = false))
        compose.onNodeWithText("ОК").assertDoesNotExist()
        compose.onNodeWithText("ОК, дальше").assertExists()
    }

    /** Срока у анализа нет: «ОК, дальше» сначала показывает цену пропуска шторкой; «Пропустить» — выход. */
    @Test
    fun okNextAsksInSheetThenSkips() {
        show(board(finished = false))
        compose.onNodeWithText(SKIP_WARNING).assertDoesNotExist()
        compose.onNodeWithText("ОК, дальше").performClick()
        compose.onNodeWithText("Пропустить анализ?").assertExists()
        compose.onNodeWithText(SKIP_WARNING).assertExists()
        assertEquals(0, oks)
        compose.onNodeWithText("Пропустить").performClick()
        compose.waitForIdle()
        assertEquals(1, oks)
    }

    @Test
    fun stayClosesSheetWithoutLeaving() {
        show(board(finished = false))
        compose.onNodeWithText("ОК, дальше").performClick()
        compose.onNodeWithText("Досмотреть").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Пропустить анализ?").assertDoesNotExist()
        assertEquals(0, oks)
    }

    @Test
    fun okOnlyWhenFinishedWithVerdict() {
        show(board(finished = true))
        compose.onNodeWithText("ОК, дальше").assertDoesNotExist()
        compose.onNodeWithText("Белые списки: вероятно 2 сервера").assertExists()
        compose.onNodeWithText("ОК").performClick()
        assertEquals(1, oks)
    }

    @Test
    fun noBoardYetSaysWhatIsGoingOn() {
        show(null)
        compose.onNodeWithText("АНАЛИЗИРУЮ ПОДПИСКУ").assertExists()
        compose.onNodeWithText("читаю подписку…").assertExists()
        compose.onNodeWithText("ОК, дальше").assertExists()
    }

    @Test
    fun sniWhiteIsItsOwnMark() {
        show(board(finished = false).copy(cards = listOf(card.copy(sniWhite = true), card.copy(key = "k2", whiteHint = WhiteHint.Unlikely, sniWhite = true))))
        compose.onNodeWithText("белые списки: вероятно · SNI белый").assertExists()
        compose.onNodeWithText("белые списки: нет · SNI белый").assertExists()
    }

    /** Десять карточек, у каждой своя страна выхода; новейшая — первой. */
    private val countries = listOf("NL", "DE", "FI", "SE", "FR", "GB", "US", "PL", "LV", "EE")
    private val ten = countries.mapIndexed { i, c -> card.copy(key = "k$i", exit = Exit.At("198.51.100.$i", c), live = false) }
    private fun routeTo(c: String) = "${flagOf("RU")} Россия → ${flagOf(c)} " + java.util.Locale.forLanguageTag("und-$c").getDisplayCountry(java.util.Locale.forLanguageTag("ru"))

    // Высокий экран: LazyColumn составляет только видимое, а здесь проверяется ровно «шесть новейших».
    @Test
    @Config(qualifiers = "h3000dp")
    fun feedComposesOnlyNewestSix() {
        show(board(finished = false).copy(cards = ten))
        countries.take(FEED_CARDS).forEach { compose.onNodeWithText(routeTo(it)).assertExists() }
        countries.drop(FEED_CARDS).forEach { compose.onNodeWithText(routeTo(it)).assertDoesNotExist() }
    }
}
