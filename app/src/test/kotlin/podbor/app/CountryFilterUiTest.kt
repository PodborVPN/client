package podbor.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import podbor.app.ui.CountryFilterButton
import podbor.app.ui.PodborTheme
import podbor.core.ExitFilter
import podbor.screen.CountrySelection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CountryFilterUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exclusionsApplyOnlyOnConfirmation() {
        val applied = mutableListOf<ExitFilter>()
        compose.setContent { PodborTheme {
            CountryFilterButton(CountrySelection(available = setOf("RU", "DE", "NL")), applied::add)
        } }
        compose.onNodeWithContentDescription("Страны выхода: все").performClick()
        compose.onNodeWithText("Россия").performClick()
        assertEquals(emptyList<ExitFilter>(), applied)
        compose.onNodeWithText("применить").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ExitFilter(setOf("RU"))), applied)
    }

    @Test fun allCountriesCanBeUncheckedTogether() {
        val applied = mutableListOf<ExitFilter>()
        compose.setContent { PodborTheme {
            CountryFilterButton(CountrySelection(available = setOf("RU", "DE")), applied::add)
        } }
        compose.onNodeWithContentDescription("Страны выхода: все").performClick()
        compose.onNodeWithText("Все страны").performClick()
        compose.onNodeWithText("применить").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ExitFilter(setOf("RU", "DE"))), applied)
    }

    @Test fun activeFilterCanBeClearedWithoutKnownCountries() {
        val applied = mutableListOf<ExitFilter>()
        compose.setContent { PodborTheme {
            CountryFilterButton(CountrySelection(filter = ExitFilter(setOf("RU")), blocked = true), applied::add)
        } }
        compose.onNodeWithContentDescription("Страны выхода: есть исключения").performClick()
        compose.onNodeWithText("Все страны").assertIsDisplayed().performClick()
        compose.onNodeWithText("применить").performClick()
        compose.waitForIdle()
        assertEquals(listOf(ExitFilter()), applied)
    }
}
