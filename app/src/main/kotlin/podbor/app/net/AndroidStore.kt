package podbor.app.net

import android.util.Log
import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.serialization.json.Json
import podbor.core.Memory
import podbor.driver.Store
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference

/** Тот же JSON, что у CLI (memory.json): память переносима между JVM-прогоном и телефоном. */
private val json = Json { allowStructuredMapKeys = true; encodeDefaults = true }

/** Можно ли писать файл памяти: после ошибки чтения существующего файла — нельзя, иначе пустая память затрёт настоящую. */
private sealed interface Disk {
    data object Writable : Disk
    data object Unreadable : Disk
}

/**
 * Память сети в `dir/memory.json`. Нет файла или он битый (не JSON) — пустая память: потеря памяти не авария.
 * Файл есть, но не читается (ошибка ввода-вывода) — пустая память на это нажатие, а запись пропускается,
 * пока чтение не удастся снова. [warn] — в logcat (тег Podbor), без содержимого памяти.
 */
class AndroidStore(dir: File, private val warn: (String) -> Unit = { Log.w("Podbor", it) }) : Store {
    private val file = File(dir, "memory.json")
    private val disk = AtomicReference<Disk>(Disk.Writable)

    override fun loadMemory(): Memory {
        if (!file.exists()) {
            disk.set(Disk.Writable)
            return Memory.Empty
        }
        return Either.catch { file.readText() }.fold(
            { e ->
                disk.set(Disk.Unreadable)
                warn("память не читается: ${e.javaClass.simpleName}; запись отключена")
                Memory.Empty
            },
            { text ->
                disk.set(Disk.Writable)
                Either.catch { json.decodeFromString(Memory.serializer(), text) }.getOrElse { Memory.Empty }
            },
        )
    }

    override fun saveMemory(m: Memory) {
        when (disk.get()) {
            Disk.Unreadable -> warn("память не записана: файл не читался")
            // Запись через временный файл и атомарный move: оборванная запись не оставляет полу-JSON.
            Disk.Writable -> Either.catch {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(Memory.serializer(), m))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.onLeft { warn("память не записана: ${it.javaClass.simpleName}") }
        }
    }
}
