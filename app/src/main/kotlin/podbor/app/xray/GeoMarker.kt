package podbor.app.xray

/*
 * Решение «копировать ли geo-файлы из APK» без Android. Метка на диске — время установки APK и размеры
 * записанных файлов: новая установка или недописанный/удалённый файл — копировать, иначе брать готовые.
 */

/** Метка после удачного копирования: первая строка — время установки, дальше `имя=размер`. */
fun geoMarker(stamp: Long, sizes: Map<String, Long>): String =
    (listOf(stamp.toString()) + sizes.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }).joinToString("\n")

/** Файлы на диске годятся: метка той же установки и каждый файл того размера, что записан ([onDisk]: null — файла нет). */
fun geoFresh(marker: String?, stamp: Long, onDisk: Map<String, Long?>): Boolean {
    val lines = marker?.lines() ?: return false
    if (lines.firstOrNull()?.toLongOrNull() != stamp) return false
    val recorded = lines.drop(1).mapNotNull { line ->
        val name = line.substringBefore('=', "")
        line.substringAfter('=', "").toLongOrNull()?.let { size -> name to size }
    }.toMap()
    return onDisk.isNotEmpty() && onDisk.all { (name, size) -> size != null && recorded[name] == size }
}
