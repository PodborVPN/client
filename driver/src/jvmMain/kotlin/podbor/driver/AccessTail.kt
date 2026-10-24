package podbor.driver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Порог файла access-лога: дорос — обрезаем; строки уже прочитаны и свёрнуты в окна ([connLoop]). */
const val ACCESS_CAP_BYTES: Long = 256L * 1024

/**
 * Хвост access-лога Xray до отмены: раз в [pollMs] — новые целые строки в [out]; недописанная ждёт конца строки. Файл
 * дорос до [capBytes] и прочитан до конца строки — обрезается до нуля: Xray пишет с O_APPEND и продолжит с начала, а
 * лог не растёт без края. Строки, дописанные между чтением и обрезкой, теряются — окно журнала это переживает. Файла
 * ещё нет (Xray не открыл) — ждём.
 */
suspend fun tailAccess(file: File, out: SendChannel<String>, pollMs: Long = 1_000, capBytes: Long = ACCESS_CAP_BYTES) {
    var offset = 0L
    var partial = ""
    while (true) {
        val chunk = withContext(Dispatchers.IO) {
            arrow.core.Either.catch {
                when {
                    !file.exists() -> ""
                    else -> RandomAccessFile(file, "rw").use { f ->
                        // Файл стал короче, чем прочитано, — его обрезали или пересоздали: читаем сначала.
                        if (f.length() < offset) offset = 0
                        val bytes = ByteArray((f.length() - offset).toInt())
                        f.seek(offset)
                        f.readFully(bytes)
                        offset += bytes.size
                        val text = partial + bytes.decodeToString()
                        partial = text.substringAfterLast('\n', missingDelimiterValue = text)
                        if (offset >= capBytes && partial.isEmpty()) {
                            f.setLength(0)
                            offset = 0
                        }
                        text.substringBeforeLast('\n', missingDelimiterValue = "")
                    }
                }
            }.getOrNull().orEmpty()
        }
        chunk.lineSequence().filter { it.isNotEmpty() }.forEach { out.send(it) }
        delay(pollMs)
    }
}
