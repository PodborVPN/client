package podbor.app.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ремонт DNS — по сети ([podbor.core.networkKey]): человек согласился узнавать адреса российских сайтов через Яндекс DNS
 * там, где DNS сети сломан. Сломанный DNS — свойство сети: на других сетях ничего не меняется. Читают экран (слова и 🔧)
 * и служба (конфиг Xray при старте сессии) — одно хранилище процесса.
 */
class DnsFixes(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(load())

    /** Сети, где починено: ключи сетей. */
    val all: StateFlow<Set<String>> = current.asStateFlow()

    fun fixed(network: String): Boolean = network in current.value

    fun set(network: String, on: Boolean) {
        val next = if (on) current.value + network else current.value - network
        prefs.edit().putStringSet(KEY, next).apply()
        current.value = next
    }

    private fun load(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private companion object {
        const val PREFS = "dns_fix"
        const val KEY = "networks"
    }
}
