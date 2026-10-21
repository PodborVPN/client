package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.ui.Aim
import podbor.app.ui.Gesture
import podbor.app.ui.Petal
import podbor.app.ui.Point
import podbor.app.ui.Radial
import podbor.app.ui.Ring
import podbor.app.ui.Thresholds
import podbor.app.ui.Touch
import podbor.app.ui.aimOf
import podbor.app.ui.radialStep

class RadialTest : FunSpec({

    val t = Thresholds(longPressMs = 400, touchSlopPx = 10f)
    val ring = Ring(center = Point(500f, 500f), innerPx = 200f)
    val center = Point(500f, 500f)
    val onOff = Point(500f, 250f) // прямо над кнопкой, за её краем

    /** Прогон букв с Idle: итоговое состояние и все жесты по порядку. */
    fun run(vararg letters: Touch): Pair<Radial, List<Gesture>> =
        letters.fold<Touch, Pair<Radial, List<Gesture>>>(Radial.Idle to emptyList()) { (s, acc), l ->
            val (next, out) = radialStep(t, ring, s, l)
            next to acc + out
        }

    test("лепесток «выкл» — сектор сверху за краем кнопки; внутри кнопки и сбоку — мимо") {
        aimOf(ring, onOff) shouldBe Aim.At(Petal.Off)
        aimOf(ring, Point(530f, 260f)) shouldBe Aim.At(Petal.Off) // чуть вбок — всё ещё сектор
        aimOf(ring, center) shouldBe Aim.Nowhere
        aimOf(ring, Point(500f, 350f)) shouldBe Aim.Nowhere // над центром, но внутри кнопки
        aimOf(ring, Point(800f, 500f)) shouldBe Aim.Nowhere // справа
        aimOf(ring, Point(500f, 800f)) shouldBe Aim.Nowhere // снизу
    }

    test("короткое касание — тап") {
        run(Touch.Down(0, center), Touch.Up(150)) shouldBe (Radial.Idle to listOf(Gesture.Tap))
    }

    test("дрожание пальца в пределах slop до порога — всё ещё тап") {
        run(Touch.Down(0, center), Touch.Move(50, Point(505f, 505f)), Touch.Up(150)).second shouldBe listOf(Gesture.Tap)
    }

    test("сдвиг дальше slop до порога — не тап и не лепесток, до конца касания") {
        run(Touch.Down(0, center), Touch.Move(50, Point(530f, 500f)), Touch.Tick(500), Touch.Up(600)) shouldBe
            (Radial.Idle to emptyList())
    }

    test("порог долгого нажатия решает буква времени: Tick раньше порога — держим, на пороге — раскрыли") {
        run(Touch.Down(0, center), Touch.Tick(399)).first shouldBe Radial.Holding(0, center)
        run(Touch.Down(0, center), Touch.Tick(400)) shouldBe (Radial.Fanned(Aim.Nowhere) to listOf(Gesture.Opened))
    }

    test("долгое нажатие, протяжка на лепесток, отпускание — выбран «выкл»") {
        run(
            Touch.Down(0, center),
            Touch.Tick(400),
            Touch.Move(500, Point(500f, 400f)),
            Touch.Move(550, onOff),
            Touch.Up(600),
        ) shouldBe (Radial.Idle to listOf(Gesture.Opened, Gesture.Aimed(Aim.At(Petal.Off)), Gesture.Chose(Petal.Off)))
    }

    test("отпустил мимо лепестка — ничего не выбрано; ушёл с лепестка — прицел снят") {
        run(Touch.Down(0, center), Touch.Tick(400), Touch.Up(500)) shouldBe (Radial.Idle to listOf(Gesture.Opened))
        run(Touch.Down(0, center), Touch.Tick(400), Touch.Move(450, onOff), Touch.Move(500, Point(800f, 500f)), Touch.Up(550)) shouldBe
            (Radial.Idle to listOf(Gesture.Opened, Gesture.Aimed(Aim.At(Petal.Off)), Gesture.Aimed(Aim.Nowhere)))
    }

    test("Move после порога без Tick тоже раскрывает — время приходит и с движением") {
        run(Touch.Down(0, center), Touch.Move(420, Point(503f, 500f))).first shouldBe Radial.Fanned(Aim.Nowhere)
    }

    test("отпустил после порога, а Tick не успел — не тап") {
        run(Touch.Down(0, center), Touch.Up(450)) shouldBe (Radial.Idle to emptyList())
    }

    test("отмена касания (система забрала жест) — в покой без жестов из любого состояния") {
        run(Touch.Down(0, center), Touch.Cancel(100)) shouldBe (Radial.Idle to emptyList())
        run(Touch.Down(0, center), Touch.Tick(400), Touch.Move(450, onOff), Touch.Cancel(500)).first shouldBe Radial.Idle
    }

    test("буквы без касания в покое ничего не делают") {
        run(Touch.Move(10, onOff), Touch.Tick(500), Touch.Up(600), Touch.Cancel(700)) shouldBe (Radial.Idle to emptyList())
    }
})
