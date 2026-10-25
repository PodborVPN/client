package podbor.app

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.ui.PodborTheme
import podbor.app.ui.RepeatDialog
import podbor.app.ui.SubscriptionScreen
import podbor.app.ui.SubscriptionSheet
import podbor.screen.Analyzed
import podbor.screen.Announce
import podbor.screen.SubSheet
import podbor.screen.SubscriptionCard
import podbor.screen.SubscriptionNote
import podbor.screen.Subscriptions
import podbor.screen.Support
import podbor.screen.Term

/** Список подписок под кнопками и шторка подписки. Robolectric, без телефона. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubscriptionListUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val note = SubscriptionNote(null, listOf("обновлена 2 ч назад"), Support.None, Announce.None)
    private val current = SubscriptionCard("a", "Мой VPN", 24, Analyzed.Done(0), Term.Unknown, note)
    private val other = SubscriptionCard("b", "Второй", 8, Analyzed.Interrupted(0, 5, 8), Term.Expired(0), note)

    private fun showList(subs: Subscriptions) = compose.setContent {
        PodborTheme {
            SubscriptionScreen(null, onInput = {}, onQr = {}, onSupport = {}, subscriptions = subs, nowMs = 0, onCard = { calls += "card ${it.id}" })
        }
    }

    private fun showSheet(sheet: SubSheet) = compose.setContent {
        PodborTheme {
            SubscriptionSheet(
                sheet, nowMs = 0,
                onPick = { calls += "pick ${it.id}" }, onReanalyze = { calls += "reanalyze ${it.id}" },
                onAskDelete = { calls += "ask ${it.id}" }, onConfirmDelete = { calls += "delete ${it.id}" },
                onSupport = { calls += "support $it" }, onDismiss = { calls += "dismiss" },
            )
        }
    }

    @Test
    fun listShowsCurrentFirstAndSelected() {
        showList(Subscriptions.Chosen(current, listOf(other)))
        compose.onNodeWithText("мои подписки").assertExists()
        compose.onNode(hasText("Мой VPN") and hasClickAction(), useUnmergedTree = false).assertIsSelected()
        compose.onNode(hasText("Второй") and hasClickAction()).assertIsNotSelected()
    }

    @Test
    fun noSubscriptionsNoList() {
        showList(Subscriptions.None)
        compose.onNodeWithText("мои подписки").assertDoesNotExist()
    }

    @Test
    fun tapOnCardOpensItsSheet() {
        showList(Subscriptions.Chosen(current, listOf(other)))
        compose.onNodeWithText("Второй").performClick()
        assertEquals(listOf("card b"), calls)
    }

    @Test
    fun expiredAndInterruptedInWords() {
        showList(Subscriptions.Chosen(current, listOf(other)))
        compose.onNode(hasText("анализ прерван", substring = true)).assertExists()
        compose.onNode(hasText("кончилась только что", substring = true)).assertExists()
    }

    @Test
    fun otherSheetHasPickReanalyzeDelete() {
        showSheet(SubSheet.Card(other, current = false))
        compose.onNodeWithText("прерван · 5 из 8").assertExists()
        compose.onNodeWithText("обновлена 2 ч назад").assertExists()
        compose.onNodeWithText("выбрать").performClick()
        compose.onNodeWithText("проанализировать заново").performClick()
        compose.onNodeWithText("удалить").performClick()
        assertEquals(listOf("pick b", "reanalyze b", "ask b"), calls)
    }

    @Test
    fun currentSheetHasNoPick() {
        showSheet(SubSheet.Card(current, current = true))
        compose.onNodeWithText("выбрать").assertDoesNotExist()
        compose.onNodeWithText("проанализировать заново").assertExists()
    }

    @Test
    fun confirmWarnsVpnWillStop() {
        showSheet(SubSheet.Confirm(current, stopsVpn = true))
        compose.onNodeWithText("Удалить подписку?").assertExists()
        compose.onNodeWithText("VPN выключится").assertExists()
        compose.onNodeWithText("удалить").performClick()
        compose.waitForIdle()
        assertEquals(listOf("delete a"), calls)
    }

    @Test
    fun confirmWithoutVpnSaysNothingAboutIt() {
        showSheet(SubSheet.Confirm(other, stopsVpn = false))
        compose.onNodeWithText("VPN выключится").assertDoesNotExist()
    }

    @Test
    fun repeatPasteAsksToRefresh() {
        compose.setContent {
            PodborTheme { RepeatDialog(other, onRefresh = { calls += "refresh" }, onDismiss = { calls += "dismiss" }) }
        }
        compose.onNodeWithText("Эта подписка уже есть в списке").assertExists()
        compose.onNodeWithText("«Второй» — обновить её и проверить заново?").assertExists()
        compose.onNodeWithText("обновить").performClick()
        compose.onNodeWithText("отмена").performClick()
        assertEquals(listOf("refresh", "dismiss"), calls)
    }

    @Test
    fun pickingSaysWhatWeDo() {
        showSheet(SubSheet.Picking(other))
        compose.onNodeWithText("обновляю подписку…").assertExists()
    }

    @Test
    fun endedLeadsToProviderSupport() {
        showSheet(SubSheet.Ended(other, Support.Link("https://t.me/help")))
        compose.onNodeWithText("подписка кончилась").assertExists()
        compose.onNodeWithText("поддержка провайдера").performClick()
        compose.waitForIdle()
        assertEquals(listOf("support https://t.me/help"), calls)
    }
}
