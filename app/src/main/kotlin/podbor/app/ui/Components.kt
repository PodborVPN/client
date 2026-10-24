package podbor.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import podbor.app.R
import podbor.core.ServiceId

/*
 * Компоненты Podbor — три вида кнопок и один каркас шторки; других нет. Экраны собираются из них и темы ([Space],
 * [Corner], [PodborColors]), без своих отступов, форм и цветов.
 */

/** Главная кнопка: залита янтарём, слова капсом; одна на экран или шторку. */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = Corner,
        colors = ButtonDefaults.buttonColors(containerColor = PodborColors.Accent, contentColor = PodborColors.Background),
        contentPadding = PaddingValues(horizontal = Space.xl, vertical = Space.m),
        modifier = modifier.defaultMinSize(minHeight = Space.touch),
    ) { Caps(text, MaterialTheme.typography.labelLarge, PodborColors.Background) }
}

/**
 * Слова капсом в разрядку — на экране; для озвучки и поиска — как написаны: капс озвучка читает по буквам.
 */
@Composable
internal fun Caps(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = color,
        style = style.copy(letterSpacing = CapsSpacing),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}

/**
 * Тихая кнопка: контур, слова — текстом [color]; значок [icon] — одним цветом [tint]: акцент тихой кнопки (жук
 * «поделиться Лентой» — янтарный, сердце «Поддержать» — розовое) или цвет текста.
 */
@Composable
internal fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tint: Color = PodborColors.Muted,
    color: Color = PodborColors.Text,
) {
    OutlinedButton(
        onClick = onClick,
        shape = Corner,
        border = BorderStroke(1.dp, Rule),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        contentPadding = PaddingValues(start = Space.m, end = Space.l, top = Space.s, bottom = Space.s),
        modifier = modifier.defaultMinSize(minHeight = Space.control),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(Space.icon))
            Spacer(Modifier.width(Space.s))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Кнопка-значок: квадрат 40dp с контуром и янтарным значком; касание — 48dp вокруг, даже в строке 40dp (выступает за
 * неё, не раздвигая).
 */
@Composable
internal fun IconSquareButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.requiredSize(Space.touch).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(Space.control).border(1.dp, Rule, Corner), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = label, tint = PodborColors.Accent, modifier = Modifier.size(Space.icon))
        }
    }
}

/** Верх шторки — те же 4dp, низ уходит за край экрана. */
private val SheetShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)

/**
 * Каркас шторки: ручка, поля [Space.xl], заголовок капсом; ниже — [content]. Смахнуть или «назад» — [onDismiss].
 * Ответы с кнопок закрывают её через [rememberSheetAnswer] того же [state].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodborSheet(
    state: SheetState,
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = SheetShape,
        containerColor = PodborColors.Surface,
        scrimColor = PodborColors.Scrim,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Rule) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl).padding(bottom = Space.xl)) {
            Caps(title, MaterialTheme.typography.titleMedium, PodborColors.Text)
            Spacer(Modifier.height(Space.m))
            content()
        }
    }
}

/** Знак сервиса — монохромный, цвета текста; сервиса без знака — нет (строка покажет метку словом). */
@DrawableRes
internal fun glyphOf(service: ServiceId): Int? = when (service.id) {
    "tg" -> R.drawable.ic_svc_telegram
    "insta" -> R.drawable.ic_svc_instagram
    else -> null
}

/** Колонка шторки с отступом между блоками — [Space.s]. */
@Composable
internal fun Gap() = Spacer(Modifier.height(Space.s))
