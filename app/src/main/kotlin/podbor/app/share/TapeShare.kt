package podbor.app.share

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import podbor.driver.anonymize
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * «Поделиться Лентой»: ленты приложения (files/tape-<мс>.jsonl) за последние дни — одним файлом в cache/share/ для
 * системного шаринга. Отдаётся анонимной ([anonymize]): без адресов, ключей, SNI, имён серверов и вышки, но
 * прогон ([podbor.driver.replay]) по ней идёт как по исходной. На телефоне Лента остаётся полной.
 */

/**
 * Окно выгрузки. Ленту присылают не сразу: эпизод белых списков не должен вытесниться, пока соберутся.
 * Лента — на жизнь сервиса (открытие ≈ 2 КБ, нажатие — десятки КБ): неделя обычно укладывается в единицы МБ.
 */
const val SHARE_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

/** Одна лента: время начала (мс из имени файла) и её строки как есть. */
data class Tape(val startedAt: Long, val text: String)

/** Склеенная Лента к отдаче: имя файла и содержимое. */
data class Bundle(val fileName: String, val text: String)

/** Почему не делимся: лент нет, файлы не прочитать/не записать. */
sealed interface Unshared {
    data object Empty : Unshared
    data object Unreadable : Unshared
}

private val TAPE_NAME = Regex("""tape-(\d+)\.jsonl""")

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

/** Каталог выгрузок внутри cacheDir: FileProvider открывает только его. */
const val SHARE_DIR = "share"

fun shareName(at: LocalDateTime): String = "podbor-tape-${STAMP.format(at)}.jsonl"

/** Склейка по времени лент, анонимная — псевдонимы общие на всю склейку; нечего склеивать — [Unshared.Empty]. */
fun bundleOf(tapes: List<Tape>, at: LocalDateTime): Either<Unshared, Bundle> {
    val texts = tapes.sortedBy { it.startedAt }.map { it.text }.filter { it.isNotBlank() }
    return if (texts.isEmpty()) {
        Unshared.Empty.left()
    } else {
        Bundle(shareName(at), anonymize(texts.joinToString("") { if (it.endsWith("\n")) it else it + "\n" })).right()
    }
}

fun wordsOf(u: Unshared): String = when (u) {
    Unshared.Empty -> "Лента пуста"
    Unshared.Unreadable -> "Ленту не прочитать"
}

/**
 * Ленты из files/: только tape-<мс>.jsonl, начатые или дописанные (mtime) не раньше [nowMs] − [windowMs] — Лента
 * одна на жизнь сервиса, а сервис живёт днями; по времени из имени. Ленты «из будущего» (часы телефона уходили
 * вперёд) берутся. Ошибка ввода-вывода — [Unshared.Unreadable].
 */
fun readTapes(dir: File, nowMs: Long, windowMs: Long = SHARE_WINDOW_MS): Either<Unshared.Unreadable, List<Tape>> = Either.catch {
    (dir.listFiles() ?: emptyArray())
        .mapNotNull { f -> TAPE_NAME.matchEntire(f.name)?.takeIf { f.isFile }?.let { m -> m.groupValues[1].toLong() to f } }
        .filter { (startedAt, f) -> maxOf(startedAt, f.lastModified()) >= nowMs - windowMs }
        .sortedBy { it.first }
        .map { (startedAt, f) -> Tape(startedAt, f.readText()) }
}.mapLeft { Unshared.Unreadable }

/** Пишет выгрузку в cache/share/, прошлые выгрузки удаляет (в кэше копится не больше одной). */
fun writeShare(cache: File, bundle: Bundle): Either<Unshared.Unreadable, File> = Either.catch {
    val dir = File(cache, SHARE_DIR)
    dir.listFiles()?.forEach { it.delete() }
    dir.mkdirs()
    File(dir, bundle.fileName).apply { writeText(bundle.text) }
}.mapLeft { Unshared.Unreadable }
