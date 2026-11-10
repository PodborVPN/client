package podbor.app.ui.country

import podbor.core.Entry

/** Название страны по-русски; неизвестный код — сам код. */
expect fun countryName(code: String): String

/** Рисует ли платформа флаги-эмодзи: в браузере Compose рисует на холсте, и цветного шрифта у него нет. */
expect val flagsDrawn: Boolean

private fun isCountry(code: String): Boolean = code.length == 2 && code.uppercase().all { it in 'A'..'Z' }

/** Региональный индикатор буквы — суррогатной парой: вне BMP. */
private fun indicatorOf(letter: Char): String = charArrayOf('\uD83C', '\uDDE6' + (letter - 'A')).concatToString()

/** Флаг из двухбуквенного кода страны (региональные индикаторы); не код или флаг нечем нарисовать — "". */
fun flagOf(code: String): String =
    if (flagsDrawn && isCountry(code)) code.uppercase().map(::indicatorOf).joinToString("") else ""

/** Короткая метка страны: флаг, а нечем его нарисовать — код; не код — «?». */
fun markOf(code: String): String = when {
    !isCountry(code) -> "?"
    flagsDrawn -> flagOf(code)
    else -> code.uppercase()
}

/** «🇳🇱 Нидерланды» по коду; не код — «?». */
fun placeOf(code: String): String =
    if (isCountry(code)) listOf(flagOf(code), countryName(code)).filter { it.isNotEmpty() }.joinToString(" ") else "?"

fun entryCode(entry: Entry): String = when (entry) {
    Entry.Ru -> "RU"
    is Entry.Foreign -> entry.country
    Entry.Unknown -> ""
}
