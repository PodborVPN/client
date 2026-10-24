package podbor.driver

import kotlinx.serialization.Serializable

/**
 * Потолок Ленты на телефоне — все файлы вместе. Активный день ≈ 1 МБ: потолок держит несколько суток — баг-репорт
 * «утром работало» с запасом, — а телефону не в тягость.
 */
const val TAPE_CAP_BYTES: Long = 5L * 1024 * 1024

/** Кусок Ленты: служба живёт днями — один файл рос бы без края, и удалить из него старое нельзя, не переписав. */
const val TAPE_CHUNK_BYTES: Long = 1L * 1024 * 1024

/** Файл Ленты: когда начат (мс, из имени) и сколько весит. */
data class TapeFile(val startedAt: Long, val bytes: Long)

/** Удалено по потолку: [bytes] Ленты, всё — раньше [beforeMs]. Дыра в наблюдениях — тоже наблюдение. */
@Serializable
data class Lost(val beforeMs: Long, val bytes: Long)

/** Что сделать с Лентой по потолку: ничего или удалить старые файлы и записать потерю. */
sealed interface Trim {
    data object Keep : Trim
    data class Drop(val files: List<TapeFile>, val lost: Lost) : Trim
}

/** Текущий файл дорос до куска — следующая строка идёт в новый. */
fun rotationDue(currentBytes: Long): Boolean = currentBytes >= TAPE_CHUNK_BYTES

/**
 * Над потолком — удаляем самые старые файлы, пока не влезем; текущий ([current] — его начало) не удаляется никогда.
 * Потеря — до начала самого старого оставшегося файла.
 */
fun trimOf(tapes: List<TapeFile>, current: Long): Trim {
    val old = tapes.filter { it.startedAt != current }.sortedBy { it.startedAt }
    var total = tapes.sumOf { it.bytes }
    val dropped = old.takeWhile { f ->
        val over = total > TAPE_CAP_BYTES
        if (over) total -= f.bytes
        over
    }
    val kept = tapes.filter { it !in dropped }
    return when {
        dropped.isEmpty() -> Trim.Keep
        else -> Trim.Drop(dropped, Lost(beforeMs = kept.minOf { it.startedAt }, bytes = dropped.sumOf { it.bytes }))
    }
}
