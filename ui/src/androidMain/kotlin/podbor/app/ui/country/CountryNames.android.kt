package podbor.app.ui.country

import java.util.Locale

private val RU = Locale.forLanguageTag("ru")

actual fun countryName(code: String): String =
    Locale.forLanguageTag("und-${code.uppercase(Locale.ROOT)}").getDisplayCountry(RU).ifEmpty { code }

actual val flagsDrawn: Boolean = true
