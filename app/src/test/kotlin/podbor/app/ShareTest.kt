package podbor.app

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import podbor.app.share.Bundle
import podbor.app.share.SHARE_WINDOW_MS
import podbor.app.share.Tape
import podbor.app.share.Unshared
import podbor.app.share.bundleOf
import podbor.app.share.readTapes
import podbor.app.share.shareName
import podbor.app.share.wordsOf
import podbor.app.share.writeShare
import java.io.File
import java.time.LocalDateTime

/** «Поделиться Лентой»: склейка лент приложения за последние 7 дней в один файл. Отдаётся анонимной. */
class ShareTest : FunSpec({
    val at = LocalDateTime.of(2026, 1, 15, 12, 0, 42)

    test("имя файла — podbor-tape-<yyyyMMdd-HHmm>.jsonl") {
        shareName(at) shouldBe "podbor-tape-20260115-1200.jsonl"
    }

    test("склейка в порядке времени файлов, а не порядка имён: 999 раньше 1000") {
        val tapes = listOf(
            Tape(1000, "{\"b\":1}\n"),
            Tape(999, "{\"a\":1}\n"),
            Tape(20000, "{\"c\":1}\n"),
        )
        bundleOf(tapes, at) shouldBe Bundle("podbor-tape-20260115-1200.jsonl", "{\"a\":1}\n{\"b\":1}\n{\"c\":1}\n").right()
    }

    test("файл без перевода строки в конце не склеивает свою последнюю строку с первой строкой следующего") {
        bundleOf(listOf(Tape(1, "{\"a\":1}"), Tape(2, "{\"b\":1}\n")), at).map { it.text } shouldBe
            "{\"a\":1}\n{\"b\":1}\n".right()
    }

    test("лента отдаётся анонимной: адрес — псевдонимом, один на всю склейку разных лент") {
        bundleOf(listOf(Tape(1, "{\"host\":\"203.0.113.7\"}\n"), Tape(2, "{\"to\":\"203.0.113.7:443\"}\n")), at) shouldBe
            Bundle("podbor-tape-20260115-1200.jsonl", "{\"host\":\"h1\"}\n{\"to\":\"h1:443\"}\n").right()
    }

    test("readTapes: ленты за последние 7 дней по времени из имени; старше — не читаются") {
        val dir = tempdir()
        val now = 1_800_000_000_000L
        listOf(now - SHARE_WINDOW_MS - 1, now - SHARE_WINDOW_MS, now - 1_000).forEach {
            File(dir, "tape-$it.jsonl").apply { writeText("$it\n"); setLastModified(it) }
        }
        readTapes(dir, now).getOrNull()!!.map { it.startedAt } shouldBe listOf(now - SHARE_WINDOW_MS, now - 1_000)
    }

    test("readTapes: лента начата 8 дней назад, но дописывалась вчера (сервис живёт днями) — отдаётся") {
        val dir = tempdir()
        val now = 1_800_000_000_000L
        val started = now - SHARE_WINDOW_MS - 86_400_000
        File(dir, "tape-$started.jsonl").apply { writeText("episode\n"); setLastModified(now - 86_400_000) }
        readTapes(dir, now).getOrNull()!!.map { it.text } shouldBe listOf("episode\n")
    }

    test("readTapes: лента «из будущего» (часы телефона уходили вперёд) не теряется") {
        val dir = tempdir()
        val now = 1_800_000_000_000L
        File(dir, "tape-${now + 3_600_000}.jsonl").writeText("ahead\n")
        readTapes(dir, now).getOrNull()!!.map { it.text } shouldBe listOf("ahead\n")
    }

    test("лент нет или все пустые → Лента пуста (Left Empty)") {
        bundleOf(emptyList(), at) shouldBe Unshared.Empty.left()
        bundleOf(listOf(Tape(1, ""), Tape(2, "\n")), at) shouldBe Unshared.Empty.left()
    }

    test("слова отказа") {
        wordsOf(Unshared.Empty) shouldBe "Лента пуста"
        wordsOf(Unshared.Unreadable) shouldBe "Ленту не прочитать"
    }

    test("readTapes: только files/tape-<мс>.jsonl; прочее в каталоге не берётся") {
        val dir = tempdir()
        File(dir, "tape-1700.jsonl").writeText("x\n")
        File(dir, "tape-99.jsonl").writeText("y\n")
        File(dir, "memory.json").writeText("{}")
        File(dir, "tape-abc.jsonl").writeText("z\n")
        File(dir, "xray").mkdirs()
        readTapes(dir, nowMs = 2_000).map { list -> list.sortedBy { it.startedAt } } shouldBe listOf(Tape(99, "y\n"), Tape(1700, "x\n")).right()
    }

    test("writeShare: файл в cache/share/, прошлые выгрузки удалены") {
        val cache = tempdir()
        File(cache, "share").mkdirs()
        File(cache, "share/podbor-tape-20260114-1000.jsonl").writeText("old")
        File(cache, "other.bin").writeText("keep")
        val file = writeShare(cache, Bundle("podbor-tape-20260115-1200.jsonl", "{\"a\":1}\n")).getOrNull()!!
        file shouldBe File(cache, "share/podbor-tape-20260115-1200.jsonl")
        file.readText() shouldBe "{\"a\":1}\n"
        File(cache, "share").list()!!.toList() shouldBe listOf("podbor-tape-20260115-1200.jsonl")
        File(cache, "other.bin").isFile shouldBe true
    }
})
