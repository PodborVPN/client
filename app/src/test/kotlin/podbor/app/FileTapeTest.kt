package podbor.app

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import podbor.app.net.FileTape
import podbor.driver.Row
import podbor.driver.TAPE_CAP_BYTES
import podbor.driver.TAPE_CHUNK_BYTES
import podbor.driver.rowOf
import java.nio.file.Files

/** Лента на диске: куски по [TAPE_CHUNK_BYTES], всё вместе — под [TAPE_CAP_BYTES], удалённое — строкой о потере. */
class FileTapeTest : FunSpec({

    test("пишет кусками, старые удаляет по потолку, о потере — строкой в текущем; Лента не теряет свежего") {
        val dir = Files.createTempDirectory("tape").toFile()
        var now = 1_000L
        val tape = FileTape(dir) { now++ }
        val line = "x".repeat(1_000)
        repeat(7_000) { tape.write(line) }
        tape.write("последняя")

        val files = dir.listFiles().orEmpty().filter { it.name.startsWith("tape-") }.sortedBy { it.name.removePrefix("tape-").removeSuffix(".jsonl").toLong() }
        files.sumOf { it.length() } shouldBeLessThanOrEqual TAPE_CAP_BYTES + TAPE_CHUNK_BYTES
        files.forEach { it.length() shouldBeLessThanOrEqual TAPE_CHUNK_BYTES + 2_000 }
        val current = files.last().readLines()
        current.last() shouldBe "последняя"
        val lost = current.mapNotNull { Either.catch { rowOf(it) }.getOrNull() }.filterIsInstance<Row.Lost>()
        lost.size shouldBe 1
        files.first().name.removePrefix("tape-").removeSuffix(".jsonl").toLong() shouldBe lost.single().lost.beforeMs
    }

    test("старые Ленты прошлых запусков тоже под потолком: при старте лишнее удаляется") {
        val dir = Files.createTempDirectory("tape").toFile()
        (1..7).forEach { i -> dir.resolve("tape-$i.jsonl").writeText("y".repeat(1_000_000)) }
        dir.resolve("чужой.txt").writeText("не Лента")
        FileTape(dir) { 100L }.write("старт")
        val left = dir.listFiles().orEmpty().map { it.name }
        left shouldContain "чужой.txt"
        left shouldContain "tape-100.jsonl"
        (left.size - 1 <= 6) shouldBe true
    }
})
