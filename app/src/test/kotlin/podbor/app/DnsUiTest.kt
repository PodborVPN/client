package podbor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import podbor.app.net.DnsFixes
import podbor.app.ui.DnsSheet
import podbor.app.ui.NetworkScreen
import podbor.app.ui.PodborTheme
import podbor.core.DnsHealth
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.Scenario
import podbor.screen.DnsLine
import podbor.screen.dnsLineOf

/** DNS сети на плашке: слова и 🔧 в строке улики, без прыжка высоты; ремонт — шторкой, только по действию; по сети. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DnsUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val card = NetworkCard.Measured(OsNetwork.Cellular("MTS", "25001"), Scenario.MobileOpen, "заграница отвечает", DnsHealth.Broken)

    @Test
    fun brokenDnsWordsAndWrenchWithoutJump() {
        var line by mutableStateOf<DnsLine>(DnsLine.Quiet)
        compose.setContent { PodborTheme { NetworkScreen(card, "1.0", dns = line, onDns = { calls += "dns" }) } }
        val quiet = compose.onRoot().getBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithContentDescription("DNS сети").assertDoesNotExist()
        line = dnsLineOf(card, fixed = false)
        compose.waitForIdle()
        compose.onNodeWithText("DNS сети не отвечает — российские сайты не откроются").assertExists()
        assertEquals(quiet, compose.onRoot().getBoundsInRoot().let { it.bottom - it.top })
        compose.onNodeWithContentDescription("DNS сети").performClick()
        assertEquals(listOf("dns"), calls)
    }

    @Test
    fun sheetRepairOrLater() {
        compose.setContent { PodborTheme { DnsSheet(fixed = false, onYes = { calls += "fix" }, onNo = { calls += "later" }) } }
        compose.onNodeWithText("Яндекс DNS", substring = true).assertExists()
        compose.onNodeWithText("Починить").performClick()
        compose.waitForIdle()
        assertEquals(listOf("fix"), calls)
    }

    @Test
    fun sheetRevertWhenFixed() {
        compose.setContent { PodborTheme { DnsSheet(fixed = true, onYes = { calls += "revert" }, onNo = { calls += "keep" }) } }
        compose.onNodeWithText("Вернуть DNS сети").performClick()
        compose.waitForIdle()
        assertEquals(listOf("revert"), calls)
    }

    @Test
    fun fixesPerNetworkSurviveRestart() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixes = DnsFixes(app)
        fixes.set("cell:25001", on = true)
        DnsFixes(app).fixed("cell:25001") shouldBeTrue true
        DnsFixes(app).fixed("wifi") shouldBeTrue false
        fixes.set("cell:25001", on = false)
        DnsFixes(app).fixed("cell:25001") shouldBeTrue false
    }

    private infix fun Boolean.shouldBeTrue(expected: Boolean) = assertEquals(expected, this)
}
