package podbor.app.ui

import android.content.SharedPreferences
import podbor.screen.Release
import podbor.screen.UpdateMemory

private const val UPDATE_TAG = "update_tag"
private const val UPDATE_URL = "update_url"

/** Память обновлялки в SharedPreferences: ключи прежние — ответ GitHub, полученный прежней версией, не теряется. */
class PrefsUpdateMemory(private val prefs: SharedPreferences) : UpdateMemory {
    override fun known(): Release? {
        val tag = prefs.getString(UPDATE_TAG, null) ?: return null
        val url = prefs.getString(UPDATE_URL, null) ?: return null
        return Release(tag, url)
    }

    override fun remember(release: Release) {
        prefs.edit().putString(UPDATE_TAG, release.tag).putString(UPDATE_URL, release.url).apply()
    }
}
