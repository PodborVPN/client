package podbor.screen

import kotlinx.serialization.Serializable
import podbor.core.ExitFilter

/** Страны текущей подписки и ограничение подбора — часть общего Status. */
@Serializable
data class CountrySelection(
    val filter: ExitFilter = ExitFilter(),
    val available: Set<String> = emptySet(),
    val blocked: Boolean = false,
    val unknown: Int = 0,
    val checking: Boolean = false,
)

sealed interface CountrySheet {
    data object Closed : CountrySheet
    data class Editing(val filter: ExitFilter) : CountrySheet
}

sealed interface CountryInput {
    data class Open(val filter: ExitFilter) : CountryInput
    data class Toggle(val country: String) : CountryInput
    data class None(val countries: Set<String>) : CountryInput
    data object All : CountryInput
    data object Apply : CountryInput
    data object Dismiss : CountryInput
}

data class CountryChange(val state: CountrySheet, val applied: List<ExitFilter> = emptyList())

/** Черновик не меняет допуск туннеля до явного применения. */
fun countryStep(state: CountrySheet, input: CountryInput): CountryChange = when (state) {
    CountrySheet.Closed -> closedCountries(input)
    is CountrySheet.Editing -> editingCountries(state, input)
}

private fun closedCountries(input: CountryInput): CountryChange = when (input) {
    is CountryInput.Open -> CountryChange(CountrySheet.Editing(input.filter))
    is CountryInput.Toggle, is CountryInput.None, CountryInput.All, CountryInput.Apply, CountryInput.Dismiss -> CountryChange(CountrySheet.Closed)
}

private fun editingCountries(state: CountrySheet.Editing, input: CountryInput): CountryChange = when (input) {
    is CountryInput.Open -> CountryChange(state)
    is CountryInput.Toggle -> {
        val excluded = state.filter.excluded
        val next = if (input.country in excluded) excluded - input.country else excluded + input.country
        CountryChange(CountrySheet.Editing(ExitFilter(next)))
    }
    is CountryInput.None -> CountryChange(CountrySheet.Editing(ExitFilter(state.filter.excluded + input.countries)))
    CountryInput.All -> CountryChange(CountrySheet.Editing(ExitFilter()))
    CountryInput.Apply -> CountryChange(CountrySheet.Closed, listOf(state.filter))
    CountryInput.Dismiss -> CountryChange(CountrySheet.Closed)
}
