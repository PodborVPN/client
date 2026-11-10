package podbor.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontFamily
import podbor.app.sub.DayMonth

/*
 * Что экраны берут у платформы: значки и шрифт из её ресурсов, местное время и порядок русских слов. Android и браузер
 * дают своё, экраны об этом не знают.
 */

/** Значки экрана. Рисунки — одни на обе платформы. */
enum class Glyph { BugReport, CellTower, Download, Favorite, Filter, Globe, More, PodborSign, ServiceInstagram, ServiceTelegram, Wrench }

@Composable
expect fun painterOf(glyph: Glyph): Painter

/** JetBrains Mono (OFL) с кириллицей: обычный и жирный. */
@Composable
expect fun podborFont(): FontFamily

/** Местное время «HH:mm». */
expect fun clockLine(atMs: Long): String

/** Местные день и месяц момента. */
expect fun dayMonthOf(atS: Long): DayMonth

/** Порядок двух слов по правилам русского языка. */
expect fun compareRussian(a: String, b: String): Int
