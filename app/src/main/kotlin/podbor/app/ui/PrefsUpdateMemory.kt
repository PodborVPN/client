package podbor.app.ui

import android.content.SharedPreferences
import podbor.screen.Release
import podbor.screen.UpdateMemory

private const val UPDATE_CHECKED = "update_checked_at"
private const val UPDATE_TAG = "update_tag"
private const val UPDATE_URL = "update_url"

/** Память обновлялки в SharedPreferences: ключи прежние — ответ GitHub, полученный прежней версией, не теряется. */
class PrefsUpdateMemory(private val prefs: SharedPreferences) : UpdateMemory {
    override fun checkedAtMs(): Long? = prefs.getLong(UPDATE_CHECKED, -1).takeIf { it >= 0 }

    override fun known(): Release? {
        val tag = prefs.getString(UPDATE_TAG, null) ?: return null
        val url = prefs.getString(UPDATE_URL, null) ?: return null
        return Release(tag, url)
    }

    override fun remember(atMs: Long, release: Release) {
        prefs.edit().putLong(UPDATE_CHECKED, atMs).putString(UPDATE_TAG, release.tag).putString(UPDATE_URL, release.url).apply()
    }
}
