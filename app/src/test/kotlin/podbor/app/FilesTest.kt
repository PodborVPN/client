package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import podbor.app.net.AndroidStore
import podbor.app.net.FileTape
import podbor.core.Memory
import java.io.File

class FilesTest : FunSpec({

    test("AndroidStore: нет файла → пустая память; сохранённая читается обратно") {
        val dir = tempdir()
        val store = AndroidStore(dir)
        store.loadMemory() shouldBe Memory.Empty
        store.saveMemory(Memory.Empty)
        File(dir, "memory.json").isFile shouldBe true
        store.loadMemory() shouldBe Memory.Empty
    }

    test("AndroidStore: битый файл → пустая память, не исключение") {
        val dir = tempdir()
        File(dir, "memory.json").writeText("{не json")
        AndroidStore(dir).loadMemory() shouldBe Memory.Empty
    }

    test("AndroidStore: файл есть, но не читается (ошибка ввода-вывода) → пустая память, и сохранение его не затирает") {
        val dir = tempdir()
        val file = File(dir, "memory.json").apply { writeText("настоящая память") }
        file.setReadable(false) shouldBe true
        val warnings = mutableListOf<String>()
        val store = AndroidStore(dir) { warnings += it }
        store.loadMemory() shouldBe Memory.Empty
        store.saveMemory(Memory.Empty)
        file.setReadable(true)
        file.readText() shouldBe "настоящая память"
        warnings.size shouldBe 2
    }

    test("AndroidStore: после сохранения временного файла не остаётся") {
        val dir = tempdir()
        AndroidStore(dir).saveMemory(Memory.Empty)
        dir.list()!!.toList() shouldBe listOf("memory.json")
    }

    test("FileTape: строки дописываются построчно") {
        val dir = tempdir()
        val tape = FileTape(dir) { 42L }
        tape.write("""{"a":1}""")
        tape.write("""{"b":2}""")
        File(dir, "tape-42.jsonl").readLines() shouldBe listOf("""{"a":1}""", """{"b":2}""")
    }
})
