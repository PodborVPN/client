@file:OptIn(ExperimentalWasmJsInterop::class)

package podbor.app.ui.country

@JsFun("(code) => { try { return new Intl.DisplayNames(['ru'], { type: 'region' }).of(code) || code } catch (e) { return code } }")
private external fun jsCountryName(code: String): String

actual fun countryName(code: String): String = jsCountryName(code.uppercase())

actual val flagsDrawn: Boolean = false
