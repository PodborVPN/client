package podbor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import java.text.Collator
import java.util.Locale
import podbor.app.R
import podbor.app.ui.country.countryName
import podbor.app.ui.country.flagOf
import podbor.core.ExitFilter
import podbor.screen.CountryInput
import podbor.screen.CountrySelection
import podbor.screen.CountrySheet
import podbor.screen.countryStep

/** Квадрат как у меню; точка означает наличие исключённых стран. */
@Composable
internal fun CountryFilterButton(selection: CountrySelection, onApply: (ExitFilter) -> Unit, modifier: Modifier = Modifier) {
    var sheet: CountrySheet by remember { mutableStateOf(CountrySheet.Closed) }
    fun send(input: CountryInput) {
        val change = countryStep(sheet, input)
        sheet = change.state
        change.applied.forEach(onApply)
    }
    val active = selection.filter.excluded.isNotEmpty()
    Box(modifier) {
        IconSquareButton(R.drawable.ic_filter, if (active) "Страны выхода: есть исключения" else "Страны выхода: все", { send(CountryInput.Open(selection.filter)) })
        if (active) Box(Modifier.align(Alignment.TopEnd).padding(Space.xs)) { Dot(PodborColors.Accent) }
    }
    when (val s = sheet) {
        CountrySheet.Closed -> Unit
        is CountrySheet.Editing -> CountryPicker(selection.available, s.filter, ::send)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPicker(available: Set<String>, draft: ExitFilter, send: (CountryInput) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetAnswer(state)
    val countries = remember(available, draft.excluded) {
        val collator = Collator.getInstance(Locale.forLanguageTag("ru"))
        (available + draft.excluded).sortedWith { a, b -> collator.compare(countryName(a), countryName(b)) }
    }
    PodborSheet(state, { close { send(CountryInput.Dismiss) } }, "Страны выхода") {
        Text("Подбирать среди отмеченных", style = MaterialTheme.typography.bodyMedium, color = Muted)
        Spacer(Modifier.height(Space.m))
        val all = when {
            draft.excluded.isEmpty() -> ToggleableState.On
            countries.all { it in draft.excluded } -> ToggleableState.Off
            else -> ToggleableState.Indeterminate
        }
        Row(Modifier.fillMaxWidth().heightIn(min = Space.touch).toggleable(
            value = all == ToggleableState.On,
            role = Role.Checkbox,
            onValueChange = { send(if (all == ToggleableState.On) CountryInput.None(countries.toSet()) else CountryInput.All) },
        ), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Все страны", style = MaterialTheme.typography.bodyMedium)
            TriStateCheckbox(all, onClick = null, colors = countryCheckboxColors())
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = Space.row * 7)) {
            items(countries, key = { it }) { code ->
                CountryRow(code, code !in draft.excluded, { send(CountryInput.Toggle(code)) }, Modifier.animateItem())
            }
        }
        if (countries.isEmpty()) Text("Страны появятся после анализа подписки", style = MaterialTheme.typography.bodySmall, color = Muted)
        Spacer(Modifier.height(Space.l))
        PrimaryButton("применить", { close { send(CountryInput.Apply) } }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun countryCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = PodborColors.Accent,
    uncheckedColor = Muted,
    checkmarkColor = PodborColors.Background,
)

@Composable
private fun CountryRow(code: String, checked: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = Space.touch).toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flagOf(code), modifier = Modifier.padding(end = Space.m))
        Text(countryName(code), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Checkbox(checked, onCheckedChange = null, colors = countryCheckboxColors())
    }
}

/** Ограничение видно и при пустом допуске, чтобы его можно было исправить. */
@Composable
internal fun CountryFilterNotice(selection: CountrySelection) {
    val message = when {
        selection.checking -> "Применяю фильтр стран…"
        selection.blocked && selection.unknown > 0 -> "Нет подтверждённых выходов в выбранных странах. Не удалось определить страну у ${selection.unknown}."
        selection.blocked -> "Нет выходов в выбранных странах"
        else -> ""
    }
    if (message.isNotEmpty()) Text(message, color = PodborColors.Accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = Space.m))
}
