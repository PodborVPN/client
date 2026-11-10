package podbor.app.net

import arrow.core.Either
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Текст в файл атомарно: пишем рядом во временный и переносим на место (ATOMIC_MOVE). Оборванная запись не оставляет
 * полу-JSON — файл либо прежний, либо новый. Сбой — Left: androidx AtomicFile не годится, он ошибку переноса только
 * пишет в лог, и запись молча пропадает.
 */
fun writeAtomic(file: File, text: String): Either<Throwable, Unit> = Either.catch {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    Unit
}
