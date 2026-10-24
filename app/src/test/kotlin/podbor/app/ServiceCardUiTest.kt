package podbor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.MainScreen
import podbor.app.ui.PodborTheme
import podbor.app.ui.SERVICE_TAG
import podbor.app.ui.ServiceLine
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.ServiceCard
import podbor.core.Stopwatch
import podbor.core.Telegram
import podbor.screen.Link
import podbor.screen.Mark
import podbor.screen.Note
import podbor.screen.ServiceRow
import podbor.screen.Tone

/**
 * Строка сервиса — две строки текста в любом состоянии: высота не прыгает, что бы ни происходило. Длинные слова —
 * в шторке по тапу.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceCardUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val long = (1..5).map { Note("строка объяснения номер $it, достаточно длинная, чтобы переноситься на узком экране", Tone.Muted) }

    private val rows = listOf(
        ServiceRow("TG", Mark.Works, "NL-7", "gRPC", emptyList(), searching = false, mini = Note("работает", Tone.Ok)),
        ServiceRow("TG", Mark.Dead, "нет рабочего сервера", null, long, searching = false, mini = Note(long.first().text, Tone.Fail)),
        ServiceRow(
            "TG", null, "подбираю выход…", null, emptyList(), searching = true, stopwatch = Stopwatch.Running(0),
            mini = Note("подбираю…", Tone.Warn),
        ),
        ServiceRow(
            "TG", Mark.Checking, "🇳🇱 Netherlands-60s(xHTTP) очень длинное имя сервера", "XHTTP", long, searching = false,
            pulse = "↓ 340 КБ/с · ↑ 12 КБ/с · ответ 2 с назад", mini = Note("сеть сменилась — проверяю", Tone.Warn),
        ),
    )

    @Test
    fun sameHeightInEveryState() {
        var row by mutableStateOf(rows.first())
        compose.setContent { PodborTheme { ServiceLine(row, onTap = {}) } }
        val height = compose.onNodeWithTag(SERVICE_TAG).getBoundsInRoot().let { it.bottom - it.top }
        rows.drop(1).forEach { r ->
            row = r
            compose.waitForIdle()
            assertEquals("строка «${r.mini.text}»", height, compose.onNodeWithTag(SERVICE_TAG).getBoundsInRoot().let { it.bottom - it.top })
        }
    }

    @Test
    fun detailsAndPulseInSheetNotOnCard() {
        val board = Board(
            NetworkCard.Measuring(OsNetwork.Wifi), Button.Red,
            listOf(ServiceCard(Telegram.id, "TG", CardStatus.NotFound(listOf("Белые списки.", "Проверил 3 входа в РФ: молчат")))),
        )
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board, Link.Up, emptyMap(), null, null,
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {},
                )
            }
        }
        // На карточке — мини-статус (первая строка объяснения), остальное — нет.
        compose.onNodeWithText("Белые списки.").assertExists()
        compose.onNodeWithText("Проверил 3 входа в РФ: молчат").assertDoesNotExist()
        compose.onNodeWithTag(SERVICE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Проверил 3 входа в РФ: молчат").assertExists()
    }

    @Test
    fun pulseInSheet() {
        val board = Board(
            NetworkCard.Measuring(OsNetwork.Wifi), Button.Green,
            listOf(ServiceCard(Telegram.id, "TG", CardStatus.Works(podbor.core.ExitRef("NL-7", podbor.core.Transport.Grpc)))),
        )
        val pulse = podbor.core.PulseView(mapOf(Telegram.id to podbor.core.PairPulse.Seen(340_000, 12_000, 2_000)), null, null)
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board, Link.Up, emptyMap(), null, null,
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {}, pulse = pulse,
                )
            }
        }
        compose.onNodeWithText("ответ 2 с назад", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(SERVICE_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("ответ 2 с назад", substring = true).assertExists()
    }
}
