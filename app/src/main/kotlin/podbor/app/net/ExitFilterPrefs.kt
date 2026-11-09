package podbor.app.net

import android.content.SharedPreferences
import androidx.core.content.edit
import podbor.core.ExitFilter
import podbor.core.knownCountry

/** Глобальные исключения стран: новые страны подписки по умолчанию разрешены. */
class ExitFilterPrefs(private val prefs: SharedPreferences) {
    fun read(): ExitFilter = ExitFilter(prefs.getStringSet("exit_excluded_countries", emptySet()).orEmpty().filter(::knownCountry).toSet())

    fun save(filter: ExitFilter) {
        prefs.edit { putStringSet("exit_excluded_countries", filter.excluded.filter(::knownCountry).toSet()) }
    }
}
