package podbor.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import podbor.app.sub.DayMonth
import podbor.ui.R

@DrawableRes
private fun drawableOf(glyph: Glyph): Int = when (glyph) {
    Glyph.BugReport -> R.drawable.ic_bug_report
    Glyph.CellTower -> R.drawable.ic_cell_tower
    Glyph.Download -> R.drawable.ic_download
    Glyph.Favorite -> R.drawable.ic_favorite
    Glyph.Filter -> R.drawable.ic_filter
    Glyph.Globe -> R.drawable.ic_globe
    Glyph.More -> R.drawable.ic_more
    Glyph.PodborSign -> R.drawable.ic_podbor_sign
    Glyph.ServiceInstagram -> R.drawable.ic_svc_instagram
    Glyph.ServiceTelegram -> R.drawable.ic_svc_telegram
    Glyph.Wrench -> R.drawable.ic_wrench
}

@Composable
actual fun painterOf(glyph: Glyph): Painter = painterResource(drawableOf(glyph))

private val FONT = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

@Composable
actual fun podborFont(): FontFamily = FONT

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

actual fun clockLine(atMs: Long): String = CLOCK.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

actual fun dayMonthOf(atS: Long): DayMonth {
    val d = Instant.ofEpochSecond(atS).atZone(ZoneId.systemDefault())
    return DayMonth(d.dayOfMonth, d.monthValue)
}

private val RUSSIAN = Collator.getInstance(Locale.forLanguageTag("ru"))

actual fun compareRussian(a: String, b: String): Int = RUSSIAN.compare(a, b)
