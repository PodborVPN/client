package podbor.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import podbor.app.R

/*
 * Тема Podbor: каркас Material3 (сетка 4dp, касание 48dp, роли текста), облик — сдержанный советский плакат: тёмный
 * фон, янтарь, моно, заголовки капсом в разрядку, углы 4dp. Цвета, отступы, формы и стили — только отсюда.
 */

/** Палитра: тёмный фон, янтарный акцент, зелёное «да», красное «нет». */
object PodborColors {
    val Background = Color(0xFF0B0D0C)
    val Surface = Color(0xFF131614)
    val Accent = Color(0xFFF2B134)
    val Fail = Color(0xFFFF5F56)
    val Ok = Color(0xFF4FD18B)
    val Text = Color(0xFFD8DBD6)

    /** Второстепенный текст и значки. */
    val Muted = Color(0xFF6F7A72)

    /** Линии: разделители, контуры тихих кнопок и карточек. */
    val Rule = Color(0xFF222622)

    /** Выключенное: неактивная большая кнопка, точка без состояния. */
    val Idle = Color(0xFF3A403C)

    /** Затемнение под шторкой. */
    val Scrim = Color.Black.copy(alpha = 0.6f)

    /** Сердечко «Поддержать»: мягкий розовый — не путать с красным ошибки [Fail]. */
    val Heart = Color(0xFFFF8FB8)
}

internal val Muted = PodborColors.Muted
internal val Rule = PodborColors.Rule
internal val Idle = PodborColors.Idle

/** Шкала отступов 4 · 8 · 12 · 16 · 24 и поля экрана; других значений нет. */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp

    /** Поля экрана по горизонтали. */
    val screen = 20.dp

    /** Касание — не меньше. */
    val touch = 48.dp

    /** Кнопка-иконка и строка сети. */
    val control = 40.dp

    /** Строка списка (сервис, событие). */
    val row = 48.dp

    /** Значок в строке и кнопке. */
    val icon = 18.dp

    /** Точка состояния. */
    val dot = 8.dp

    /** Линия: разделитель, контур. */
    val hairline = 1.dp
}

/** Углы — 4dp у всего, кроме круга большой кнопки и точки состояния. */
val Corner = RoundedCornerShape(4.dp)

/** Приглушённое: прошлое значение, пока идёт новый замер. */
const val DIM = 0.4f

/** Разрядка заголовков капсом и подписей ярусов. */
val CapsSpacing = 0.08.em
val TierSpacing = 0.14.em

/** JetBrains Mono (OFL) с кириллицей: обычный и жирный. */
val PodborFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private val colors = darkColorScheme(
    primary = PodborColors.Accent,
    onPrimary = PodborColors.Background,
    background = PodborColors.Background,
    onBackground = PodborColors.Text,
    surface = PodborColors.Surface,
    onSurface = PodborColors.Text,
    error = PodborColors.Fail,
    onError = PodborColors.Background,
    tertiary = PodborColors.Ok,
    onTertiary = PodborColors.Background,
    outline = PodborColors.Rule,
    scrim = PodborColors.Scrim,
)

private fun mono(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PodborFont, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 6).sp)

/** Все роли текста — моно: стиль, не заданный здесь, упал бы в sans-serif Material3. */
private val typography = Typography(
    displayLarge = mono(32, FontWeight.Bold),
    displayMedium = mono(28, FontWeight.Bold),
    displaySmall = mono(24, FontWeight.Bold),
    headlineLarge = mono(24, FontWeight.Bold),
    headlineMedium = mono(22, FontWeight.Bold),
    headlineSmall = mono(20, FontWeight.Bold),
    titleLarge = mono(18, FontWeight.Bold),
    titleMedium = mono(16, FontWeight.Bold),
    titleSmall = mono(14, FontWeight.Bold),
    bodyLarge = mono(15),
    bodyMedium = mono(13),
    bodySmall = mono(12),
    labelLarge = mono(13, FontWeight.Bold),
    labelMedium = mono(12, FontWeight.Bold),
    labelSmall = mono(11),
)

private val shapes = Shapes(
    extraSmall = Corner,
    small = Corner,
    medium = Corner,
    large = Corner,
    extraLarge = Corner,
)

@Composable
fun PodborTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
