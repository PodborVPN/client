package podbor.app.ui.country

import java.util.Locale
import podbor.core.Entry

private val RU = Locale.forLanguageTag("ru")

fun countryName(code: String): String =
    Locale.forLanguageTag("und-${code.uppercase(Locale.ROOT)}").getDisplayCountry(RU).ifEmpty { code }

/** Флаг из двухбуквенного кода страны (региональные индикаторы); не код — "". */
fun flagOf(code: String): String {
    val c = code.uppercase(Locale.ROOT)
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
    return c.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

/** «🇳🇱 Нидерланды» по коду; не код — «?». */
fun placeOf(code: String): String {
    val flag = flagOf(code)
    if (flag.isEmpty()) return "?"
    val name = countryName(code)
    return "$flag $name"
}

fun entryCode(entry: Entry): String = when (entry) {
    Entry.Ru -> "RU"
    is Entry.Foreign -> entry.country
    Entry.Unknown -> ""
}
