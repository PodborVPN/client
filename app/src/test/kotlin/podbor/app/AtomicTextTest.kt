package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import arrow.core.Either
import podbor.app.net.writeAtomic
import java.io.File
import java.nio.file.Files

/** Атомарная запись текста: файл всегда целый — прежний или новый, недописанный хвост его не портит. */
class AtomicTextTest : FunSpec({

    fun dir(): File = Files.createTempDirectory("atomic").toFile()

    test("запись и перезапись — файл целиком, каталог создаётся") {
        val f = File(dir(), "sub/a.json")
        writeAtomic(f, "один") shouldBe Either.Right(Unit)
        writeAtomic(f, "два") shouldBe Either.Right(Unit)
        f.readText() shouldBe "два"
    }

    test("оборванная прошлая запись (недописанный временный файл) прежний файл не трогает и следующей не мешает") {
        val d = dir()
        val f = File(d, "a.json")
        writeAtomic(f, "целый") shouldBe Either.Right(Unit)
        File(d, "a.json.tmp").writeText("{полу")
        f.readText() shouldBe "целый"
        writeAtomic(f, "новый") shouldBe Either.Right(Unit)
        f.readText() shouldBe "новый"
    }

    test("писать некуда — Left, без исключения") {
        val blocker = File(dir(), "file").apply { writeText("x") }
        writeAtomic(File(blocker, "a.json"), "t").shouldBeInstanceOf<Either.Left<Throwable>>()
    }

    test("не перенеслось на место — Left, а не тихая потеря") {
        val d = dir()
        File(d, "a.json").mkdirs()
        File(d, "a.json/занято").writeText("x")
        writeAtomic(File(d, "a.json"), "t").shouldBeInstanceOf<Either.Left<Throwable>>()
    }
})
