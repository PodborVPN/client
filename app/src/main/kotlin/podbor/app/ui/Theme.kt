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
import androidx.compose.ui.unit.sp
import podbor.app.R

/** Палитра темы A: тёмный фон, янтарный акцент, зелёное «да», красное «нет». */
object PodborColors {
    val Background = Color(0xFF0B0D0C)
    val Surface = Color(0xFF131614)
    val Accent = Color(0xFFF2B134)
    val Fail = Color(0xFFFF5F56)
    val Ok = Color(0xFF4FD18B)
    val Text = Color(0xFFD8DBD6)
}

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
)

private fun mono(size: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PodborFont, fontWeight = weight, fontSize = size.sp, lineHeight = (size + 6).sp)

private val typography = Typography(
    displayLarge = mono(32, FontWeight.Bold),
    headlineMedium = mono(22, FontWeight.Bold),
    titleMedium = mono(16, FontWeight.Bold),
    bodyLarge = mono(15),
    bodyMedium = mono(13),
    labelLarge = mono(13, FontWeight.Bold),
    labelSmall = mono(11),
)

/** Формы без скруглений; круглую кнопку и карточку текущего VPN (12.dp) задаёт сам экран. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(0),
    small = RoundedCornerShape(0),
    medium = RoundedCornerShape(0),
    large = RoundedCornerShape(0),
    extraLarge = RoundedCornerShape(0),
)

@Composable
fun PodborTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
