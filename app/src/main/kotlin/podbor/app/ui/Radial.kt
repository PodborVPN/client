package podbor.app.ui

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * Радиальная кнопка без Compose: жест — машина Мили, время — буква. Compose подаёт касания и рисует состояние,
 * решения — здесь, поэтому жест проверяется JVM-тестами и проигрывается буквами.
 */

/** Точка касания в пикселях экрана. */
data class Point(val x: Float, val y: Float)

/** Лепесток вокруг большой кнопки. */
enum class Petal { Off }

/** Куда смотрит палец: «никуда» — тоже значение, а не null. */
sealed interface Aim {
    data object Nowhere : Aim
    data class At(val petal: Petal) : Aim
}

/** Пороги жеста — системные (ViewConfiguration): машина их не выдумывает. */
data class Thresholds(val longPressMs: Long, val touchSlopPx: Float)

/** Кольцо лепестков: центр кнопки и радиус, за которым начинаются лепестки (край кнопки). */
data class Ring(val center: Point, val innerPx: Float)

/** Касание — буква машины; каждое несёт свой момент. Tick — время без движения: порог долгого нажатия видно и так. */
sealed interface Touch {
    val atMs: Long

    data class Down(override val atMs: Long, val at: Point) : Touch
    data class Move(override val atMs: Long, val at: Point) : Touch
    data class Up(override val atMs: Long) : Touch
    data class Cancel(override val atMs: Long) : Touch
    data class Tick(override val atMs: Long) : Touch
}

/** Состояние жеста. Dropped — палец ушёл дальше slop до порога: жест не наш до отпускания. */
sealed interface Radial {
    data object Idle : Radial
    data class Holding(val downMs: Long, val origin: Point) : Radial
    data class Fanned(val aim: Aim) : Radial
    data object Dropped : Radial
}

/** Что жест сказал наружу: тап, раскрытие лепестков, смена прицела (для вибрации), выбор лепестка. */
sealed interface Gesture {
    data object Tap : Gesture
    data object Opened : Gesture
    data class Aimed(val aim: Aim) : Gesture
    data class Chose(val petal: Petal) : Gesture
}

/** Половина ширины сектора лепестка в градусах. */
private const val PETAL_HALF_DEG = 35.0

/** Угол середины сектора «выкл»: прямо вверх (ось y экрана растёт вниз). */
private const val OFF_DEG = -90.0

/** Попадание пальца в лепесток: за краем кнопки и внутри сектора. */
fun aimOf(ring: Ring, p: Point): Aim {
    val dx = (p.x - ring.center.x).toDouble()
    val dy = (p.y - ring.center.y).toDouble()
    val outside = hypot(dx, dy) >= ring.innerPx
    val deg = Math.toDegrees(atan2(dy, dx))
    return if (outside && abs(deg - OFF_DEG) <= PETAL_HALF_DEG) Aim.At(Petal.Off) else Aim.Nowhere
}

/** Машина жеста: чистая и тотальная. */
fun radialStep(t: Thresholds, ring: Ring, s: Radial, letter: Touch): Pair<Radial, List<Gesture>> = when (s) {
    Radial.Idle -> when (letter) {
        is Touch.Down -> Radial.Holding(letter.atMs, letter.at) to emptyList()
        is Touch.Move, is Touch.Up, is Touch.Cancel, is Touch.Tick -> s to emptyList()
    }
    is Radial.Holding -> holding(t, ring, s, letter)
    is Radial.Fanned -> when (letter) {
        is Touch.Move -> aimOf(ring, letter.at).let { aim ->
            Radial.Fanned(aim) to if (aim == s.aim) emptyList() else listOf(Gesture.Aimed(aim))
        }
        is Touch.Up -> Radial.Idle to when (val aim = s.aim) {
            Aim.Nowhere -> emptyList()
            is Aim.At -> listOf(Gesture.Chose(aim.petal))
        }
        is Touch.Cancel -> Radial.Idle to emptyList()
        is Touch.Down, is Touch.Tick -> s to emptyList()
    }
    Radial.Dropped -> when (letter) {
        is Touch.Up, is Touch.Cancel -> Radial.Idle to emptyList()
        is Touch.Down, is Touch.Move, is Touch.Tick -> s to emptyList()
    }
}

private fun holding(t: Thresholds, ring: Ring, s: Radial.Holding, letter: Touch): Pair<Radial, List<Gesture>> {
    val held = letter.atMs - s.downMs >= t.longPressMs
    return when (letter) {
        is Touch.Move -> when {
            hypot((letter.at.x - s.origin.x).toDouble(), (letter.at.y - s.origin.y).toDouble()) > t.touchSlopPx && !held ->
                Radial.Dropped to emptyList()
            held -> fanned(aimOf(ring, letter.at))
            else -> s to emptyList()
        }
        is Touch.Tick -> if (held) fanned(Aim.Nowhere) else s to emptyList()
        // Отпустил после порога, не дождавшись Tick: держал долго — это не тап.
        is Touch.Up -> Radial.Idle to if (held) emptyList() else listOf(Gesture.Tap)
        is Touch.Cancel -> Radial.Idle to emptyList()
        // Второй палец жест не перезапускает.
        is Touch.Down -> s to emptyList()
    }
}

private fun fanned(aim: Aim): Pair<Radial, List<Gesture>> =
    Radial.Fanned(aim) to listOfNotNull(Gesture.Opened, Gesture.Aimed(aim).takeIf { aim != Aim.Nowhere })
