package podbor.app

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import podbor.screen.Announce
import podbor.screen.InputError
import podbor.screen.Support
import podbor.screen.SubscriptionNote
import podbor.app.sub.wordsOf
import podbor.app.ui.MainScreen
import podbor.app.ui.PodborTheme
import podbor.app.ui.SubscriptionScreen
import podbor.core.HARDCODED_SERVICES
import podbor.core.board
import podbor.screen.Link

/** Экран «Добавь подписку» и вход в него с главного («⋮» → «Сменить подписку»). Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val inputs = mutableListOf<String>()
    private val calls = mutableListOf<String>()

    private fun show(error: InputError? = null) = compose.setContent {
        PodborTheme {
            SubscriptionScreen(error, onInput = { inputs += it }, onQr = { calls += "qr" }, onSupport = { calls += "support $it" })
        }
    }

    @Test
    fun pasteHandsClipboardTextToInput() {
        RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("", "vless://сервер"))
        show()
        compose.onNodeWithText("ДОБАВЬ ПОДПИСКУ").assertExists()
        compose.onNodeWithText("вставить из буфера").performClick()
        assertEquals(listOf("vless://сервер"), inputs)
    }

    @Test
    fun emptyClipboardStillGoesToInput() {
        RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        show()
        compose.onNodeWithText("вставить из буфера").performClick()
        // Пусто — решает inputOf (Empty → слова), экран не судит сам.
        assertEquals(listOf(""), inputs)
    }

    @Test
    fun qrButtonAsksForScanner() {
        show()
        compose.onNodeWithText("QR").performClick()
        assertEquals(listOf("qr"), calls)
    }

    @Test
    fun errorShownInWords() {
        show(InputError.Download(whitelist = true))
        compose.onNodeWithText(wordsOf(InputError.Download(whitelist = true))).assertExists()
        compose.onNodeWithText("Поддержка провайдера").assertDoesNotExist()
    }

    @Test
    fun deviceLimitLeadsToProviderSupport() {
        show(InputError.DeviceLimit(Announce.Text("Отвяжи устройство в боте"), Support.Link("https://t.me/help")))
        compose.onNodeWithText("лимит устройств подписки: Отвяжи устройство в боте").assertExists()
        compose.onNodeWithText("Поддержка провайдера").performClick()
        assertEquals(listOf("support https://t.me/help"), calls)
    }

    @Test
    fun deviceLimitWithoutSupportUrlHasNoButton() {
        show(InputError.DeviceLimit(Announce.None, Support.None))
        compose.onNodeWithText("Поддержка провайдера").assertDoesNotExist()
    }

    @Test
    fun menuOnMainLeadsToChangeSubscription() {
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board(HARDCODED_SERVICES, emptyMap()), Link.Off, emptyMap(), null, null,
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {},
                    onChangeSubscription = { calls += "change" },
                )
            }
        }
        compose.onNodeWithText("Сменить подписку").assertDoesNotExist()
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("Сменить подписку").performClick()
        assertEquals(listOf("change"), calls)
    }

    /** Сведения провайдера: в «⋮» — имя и строки; поддержка — ссылкой провайдера; объявление — на экране. */
    @Test
    fun menuShowsProviderNoteAndSupport() {
        val note = SubscriptionNote(
            "Мой VPN", listOf("обновлена 2 ч назад", "оплачена до конца года"), Support.Link("https://t.me/s"), Announce.Text("Оплата до пятницы"),
        )
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board(HARDCODED_SERVICES, emptyMap()), Link.Off, emptyMap(), null, note,
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {},
                    onChangeSubscription = {}, onSupport = { calls += it },
                )
            }
        }
        compose.onNodeWithText("Оплата до пятницы").assertExists()
        compose.onNodeWithText("Мой VPN").assertDoesNotExist()
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("Мой VPN").assertExists()
        compose.onNodeWithText("обновлена 2 ч назад").assertExists()
        compose.onNodeWithText("оплачена до конца года").assertExists()
        compose.onNodeWithText("Поддержка провайдера").performClick()
        assertEquals(listOf("https://t.me/s"), calls)
    }

    @Test
    fun noSupportUrlNoSupportItem() {
        compose.setContent {
            PodborTheme {
                MainScreen(
                    board(HARDCODED_SERVICES, emptyMap()), Link.Off, emptyMap(), null, SubscriptionNote(null, listOf("добавлена текстом — сама не обновляется"), Support.None, Announce.None),
                    onPress = {}, onOff = {}, onOn = {}, onRetry = {}, onShare = {}, onChangeSubscription = {},
                )
            }
        }
        compose.onNodeWithText("⋮").performClick()
        compose.onNodeWithText("добавлена текстом — сама не обновляется").assertExists()
        compose.onNodeWithText("Поддержка провайдера").assertDoesNotExist()
    }
}
