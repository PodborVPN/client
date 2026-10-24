package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Потолок Ленты: файлы по [TAPE_CHUNK_BYTES], все вместе — не больше [TAPE_CAP_BYTES]; удалённое — строкой о потере. */
class TapeCeilingTest : FunSpec({

    val mb = 1_048_576L

    test("текущий файл дорос до куска — пора начинать новый; меньше — пишем в него") {
        rotationDue(TAPE_CHUNK_BYTES - 1) shouldBe false
        rotationDue(TAPE_CHUNK_BYTES) shouldBe true
    }

    test("всё вместе под потолком — ничего не удаляем") {
        val tapes = listOf(TapeFile(1, 2 * mb), TapeFile(2, 2 * mb), TapeFile(3, mb / 2))
        trimOf(tapes, current = 3) shouldBe Trim.Keep
    }

    test("над потолком — удаляем самые старые, пока не влезем; потеря — до начала самого старого оставшегося") {
        val tapes = listOf(TapeFile(30, mb), TapeFile(10, 2 * mb), TapeFile(20, 2 * mb), TapeFile(40, mb))
        trimOf(tapes, current = 40) shouldBe Trim.Drop(listOf(TapeFile(10, 2 * mb)), Lost(beforeMs = 20, bytes = 2 * mb))
    }

    test("текущий не удаляется никогда, даже если сам больше потолка") {
        val tapes = listOf(TapeFile(1, mb), TapeFile(2, 6 * mb))
        trimOf(tapes, current = 2) shouldBe Trim.Drop(listOf(TapeFile(1, mb)), Lost(beforeMs = 2, bytes = mb))
        trimOf(listOf(TapeFile(2, 6 * mb)), current = 2) shouldBe Trim.Keep
    }

    test("строка о потере — в Ленте и читается обратно") {
        rowOf(rowLine(Row.Lost(Lost(beforeMs = 20, bytes = 2 * mb)))) shouldBe Row.Lost(Lost(beforeMs = 20, bytes = 2 * mb))
    }
})
