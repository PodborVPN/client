package podbor.app.net

import podbor.driver.Row
import podbor.driver.TapeFile
import podbor.driver.TapeSink
import podbor.driver.Trim
import podbor.driver.rotationDue
import podbor.driver.rowLine
import podbor.driver.trimOf
import java.io.File

private val TAPE_NAME = Regex("""tape-(\d+)\.jsonl""")

/** Файлы Ленты в [dir]: tape-<мс>.jsonl — время начала из имени и вес. */
fun tapeFilesIn(dir: File): List<Pair<TapeFile, File>> =
    dir.listFiles().orEmpty().mapNotNull { f ->
        TAPE_NAME.matchEntire(f.name)?.takeIf { f.isFile }?.let { m -> TapeFile(m.groupValues[1].toLong(), f.length()) to f }
    }

/**
 * Лента в файлы JSONL (строки Ленты, driver/Tape.kt) в [dir]: кусками ([rotationDue]) — новый кусок tape-<мс>.jsonl
 * по [clock]; все вместе — под потолком ([trimOf]): старое удаляется при старте и на каждом новом куске, о потере —
 * строка [Row.Lost] в текущем. Одна на процесс: замер линии до подъёма и сессия пишут из разных корутин — строка
 * дописывается целиком.
 */
class FileTape(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) : TapeSink {
    private var startedAt = clock()
    private val current: File get() = File(dir, "tape-$startedAt.jsonl")

    init {
        trim()
    }

    @Synchronized
    override fun write(line: String) {
        if (rotationDue(current.length())) {
            // Новый кусок — строго позже текущего: имя файла — его начало, и оно не должно совпасть.
            startedAt = maxOf(clock(), startedAt + 1)
            trim()
        }
        current.appendText(line + "\n")
    }

    private fun trim() {
        val tapes = tapeFilesIn(dir).filter { it.first.startedAt != startedAt }
        when (val t = trimOf(tapes.map { it.first } + TapeFile(startedAt, current.length()), startedAt)) {
            Trim.Keep -> Unit
            is Trim.Drop -> {
                tapes.filter { it.first in t.files }.forEach { it.second.delete() }
                current.appendText(rowLine(Row.Lost(t.lost)) + "\n")
            }
        }
    }
}
