package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files

/** Хвост access-лога Xray: целые строки по мере дописывания; файл перерос порог — обрезается и не растёт без края. */
class AccessTailTest : FunSpec({

    test("отдаёт целые строки по мере дописывания; недописанную — когда допишется") {
        val file = Files.createTempFile("access", ".log").toFile()
        val out = Channel<String>(Channel.UNLIMITED)
        runBlocking {
            val job = launch { tailAccess(file, out, pollMs = 5, capBytes = 1_000_000) }
            file.appendText("первая\nвто")
            withTimeout(2_000) { out.receive() } shouldBe "первая"
            file.appendText("рая\n")
            withTimeout(2_000) { out.receive() } shouldBe "вторая"
            job.cancel()
        }
    }

    test("перерос порог — обрезается (Xray пишет с O_APPEND — дальше с нуля), строки после обрезки — тоже наши") {
        val file = Files.createTempFile("access", ".log").toFile()
        val out = Channel<String>(Channel.UNLIMITED)
        runBlocking {
            val job = launch { tailAccess(file, out, pollMs = 5, capBytes = 100) }
            file.appendText("x".repeat(150) + "\n")
            withTimeout(2_000) { out.receive() }.length shouldBe 150
            withTimeout(2_000) { while (file.length() > 0) kotlinx.coroutines.delay(5) }
            file.appendText("после\n")
            withTimeout(2_000) { out.receive() } shouldBe "после"
            file.length() shouldBeLessThan 100
            job.cancel()
        }
    }
})
