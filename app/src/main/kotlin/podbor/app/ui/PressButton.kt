package podbor.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import podbor.app.R
import podbor.core.Button

/** Тег большой кнопки для UI-тестов. */
const val PRESS_TAG = "press"

/** Радиус кнопки: за ним начинаются лепестки. */
private val BUTTON_RADIUS = 80.dp

/** Радиус дуги лепестка — между кнопкой и внешним кольцом. */
private val PETAL_RADIUS = 110.dp

/**
 * Большая кнопка. Тап — подбор (или «включить», если VPN выключен); долгое нажатие раскрывает лепесток «выкл»,
 * протяжка на него и отпускание — выключить VPN. Жест решает машина [radialStep] — здесь только буквы касаний и рисунок.
 */
@Composable
internal fun PressButton(face: Face, onPress: () -> Unit, onOff: () -> Unit, onOn: () -> Unit) {
    val button = when (face) {
        is Face.Press -> face.button
        Face.TurnOn -> Button.Ready
        Face.Waiting -> Button.Idle
    }
    val label = when (face) {
        is Face.Press -> when (face.button) {
            Button.Idle, Button.Ready -> "ПОДОБРАТЬ"
            Button.Searching -> "ИЩУ…"
            Button.Green -> "РАБОТАЕТ"
            Button.Red -> "НЕ НАШЁЛ"
        }
        Face.TurnOn -> "ВКЛЮЧИТЬ"
        Face.Waiting -> "ПОДОБРАТЬ"
    }
    val tap: (() -> Unit)? = when (face) {
        is Face.Press -> when (face.button) {
            Button.Ready, Button.Green, Button.Red -> onPress
            Button.Idle, Button.Searching -> null
        }
        Face.TurnOn -> onOn
        Face.Waiting -> null
    }
    // Лепестки — только при поднятом VPN: выключать нечего, пока его нет.
    val petals = when (face) {
        is Face.Press -> true
        Face.TurnOn, Face.Waiting -> false
    }
    val color = when (button) {
        Button.Idle -> Idle
        Button.Ready, Button.Searching, Button.Green, Button.Red -> colorOf(toneOf(button))
    }
    val icon = when (shieldOf(button)) {
        Shield.Plain -> R.drawable.ic_shield
        Shield.Check -> R.drawable.ic_shield_check
        Shield.Cross -> R.drawable.ic_shield_cross
    }
    // Пульс поиска: свечение дышит, от основного кольца расходится волна.
    // Состояние анимации читается в фазе рисования (drawBehind), не в композиции: кадр пульса не перекомпонует экран.
    val pulse: State<Float>? = when (button) {
        Button.Searching -> rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "wave",
        )
        Button.Idle, Button.Ready, Button.Green, Button.Red -> null
    }

    var radial by remember { mutableStateOf<Radial>(Radial.Idle) }
    val haptic = LocalHapticFeedback.current
    val currentTap by rememberUpdatedState(tap)
    val currentOff by rememberUpdatedState(onOff)
    val currentPetals by rememberUpdatedState(petals)
    val fan = (radial as? Radial.Fanned)?.takeIf { petals }

    fun act(g: Gesture) = when (g) {
        Gesture.Tap -> currentTap?.invoke() ?: Unit
        Gesture.Opened -> if (currentPetals) haptic.performHapticFeedback(HapticFeedbackType.LongPress) else Unit
        is Gesture.Aimed -> when (g.aim) {
            Aim.Nowhere -> Unit
            is Aim.At -> if (currentPetals) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) else Unit
        }
        is Gesture.Chose -> when (g.petal) {
            Petal.Off -> if (currentPetals) currentOff() else Unit
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp)
            .height(250.dp)
            .testTag(PRESS_TAG)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                val t = tap
                if (t != null) onClick(label) { t(); true } else disabled()
                if (petals) customActions = listOf(CustomAccessibilityAction("Выключить VPN") { onOff(); true })
            }
            .pointerInput(Unit) {
                val limits = Thresholds(viewConfiguration.longPressTimeoutMillis, viewConfiguration.touchSlop)
                val ring = Ring(Point(size.width / 2f, size.height / 2f), BUTTON_RADIUS.toPx())
                fun feed(letter: Touch) {
                    val (next, out) = radialStep(limits, ring, radial, letter)
                    radial = next
                    out.forEach(::act)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Прошлый жест оборвался (перезапуск pointerInput): машина узнаёт об этом буквой, а не сбросом.
                    if (radial != Radial.Idle) feed(Touch.Cancel(down.uptimeMillis))
                    feed(Touch.Down(down.uptimeMillis, Point(down.position.x, down.position.y)))
                    // Порог — момент от касания: Tick несёт его, а не показания часов.
                    val threshold = down.uptimeMillis + limits.longPressMs
                    var last = down.uptimeMillis
                    while (radial != Radial.Idle) {
                        val event = when {
                            radial !is Radial.Holding -> awaitPointerEvent()
                            last >= threshold -> null
                            else -> withTimeoutOrNull(threshold - last) { awaitPointerEvent() }
                        }
                        val change = event?.changes?.firstOrNull { it.id == down.id }
                        when {
                            event == null -> feed(Touch.Tick(threshold)).also { last = threshold }
                            change == null -> feed(Touch.Cancel(last))
                            // Жест забрал другой (прокрутка экрана): отмена, а не наш ход.
                            change.isConsumed -> feed(Touch.Cancel(change.uptimeMillis))
                            change.changedToUp() -> {
                                feed(Touch.Up(change.uptimeMillis))
                                change.consume()
                            }
                            else -> {
                                feed(Touch.Move(change.uptimeMillis, Point(change.position.x, change.position.y)))
                                last = change.uptimeMillis
                                // Раскрытые лепестки держат палец: прокрутка экрана его не заберёт.
                                if (radial is Radial.Fanned) change.consume()
                            }
                        }
                    }
                }
            }
            .drawBehind {
                val p = pulse?.value
                val glow = when (button) {
                    Button.Idle -> 0f
                    Button.Searching -> 0.22f + 0.18f * (1f - (p ?: 0f))
                    Button.Ready, Button.Green, Button.Red -> 0.28f
                }
                val c = Offset(size.width / 2, size.height / 2)
                val main = 100.dp.toPx()
                drawCircle(
                    Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent), center = c, radius = 125.dp.toPx()),
                    radius = 125.dp.toPx(),
                    center = c,
                )
                drawCircle(color.copy(alpha = 0.35f), radius = 116.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
                drawCircle(color, radius = main, center = c, style = Stroke(4.dp.toPx()))
                drawCircle(
                    color.copy(alpha = 0.7f),
                    radius = 88.dp.toPx(),
                    center = c,
                    style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()))),
                )
                p?.let { w ->
                    drawCircle(color.copy(alpha = 0.6f * (1f - w)), radius = main + 25.dp.toPx() * w, center = c, style = Stroke(3.dp.toPx()))
                }
                fan?.let { f ->
                    val r = PETAL_RADIUS.toPx()
                    val aimed = f.aim == Aim.At(Petal.Off)
                    drawArc(
                        color = if (aimed) PodborColors.Fail else PodborColors.Fail.copy(alpha = 0.45f),
                        startAngle = -125f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(2 * r, 2 * r),
                        style = Stroke(22.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        fan?.let {
            Text(
                "ВЫКЛ",
                color = MaterialTheme.colorScheme.background,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.08.em),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 7.dp),
            )
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.background,
            contentColor = color,
            modifier = Modifier.size(BUTTON_RADIUS * 2).clip(CircleShape),
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text(label, color = color, style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.08.em))
            }
        }
    }
}
