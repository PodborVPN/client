@file:OptIn(ExperimentalWasmJsInterop::class)

package podbor.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource
import podbor.app.sub.DayMonth
import podbor.app.ui.res.Res
import podbor.app.ui.res.ic_bug_report
import podbor.app.ui.res.ic_cell_tower
import podbor.app.ui.res.ic_download
import podbor.app.ui.res.ic_favorite
import podbor.app.ui.res.ic_filter
import podbor.app.ui.res.ic_globe
import podbor.app.ui.res.ic_more
import podbor.app.ui.res.ic_podbor_sign
import podbor.app.ui.res.ic_svc_instagram
import podbor.app.ui.res.ic_svc_telegram
import podbor.app.ui.res.ic_wrench
import podbor.app.ui.res.jetbrains_mono_bold
import podbor.app.ui.res.jetbrains_mono_regular

private fun drawableOf(glyph: Glyph): DrawableResource = when (glyph) {
    Glyph.BugReport -> Res.drawable.ic_bug_report
    Glyph.CellTower -> Res.drawable.ic_cell_tower
    Glyph.Download -> Res.drawable.ic_download
    Glyph.Favorite -> Res.drawable.ic_favorite
    Glyph.Filter -> Res.drawable.ic_filter
    Glyph.Globe -> Res.drawable.ic_globe
    Glyph.More -> Res.drawable.ic_more
    Glyph.PodborSign -> Res.drawable.ic_podbor_sign
    Glyph.ServiceInstagram -> Res.drawable.ic_svc_instagram
    Glyph.ServiceTelegram -> Res.drawable.ic_svc_telegram
    Glyph.Wrench -> Res.drawable.ic_wrench
}

@Composable
actual fun painterOf(glyph: Glyph): Painter = painterResource(drawableOf(glyph))

@Composable
actual fun podborFont(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_bold, FontWeight.Bold),
)

@JsFun("(ms) => new Date(ms).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', hour12: false })")
private external fun jsClock(ms: Double): String

actual fun clockLine(atMs: Long): String = jsClock(atMs.toDouble())

@JsFun("(ms) => new Date(ms).getDate()")
private external fun jsDay(ms: Double): Int

@JsFun("(ms) => new Date(ms).getMonth() + 1")
private external fun jsMonth(ms: Double): Int

actual fun dayMonthOf(atS: Long): DayMonth {
    val ms = atS * 1000.0
    return DayMonth(jsDay(ms), jsMonth(ms))
}

@JsFun("(a, b) => a.localeCompare(b, 'ru')")
private external fun jsCompareRussian(a: String, b: String): Int

actual fun compareRussian(a: String, b: String): Int = jsCompareRussian(a, b)
