package podbor.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import podbor.core.Deed
import podbor.core.Letter
import podbor.core.ServiceId
import podbor.core.Telegram

/**
 * Версия формата Ленты. v0 (первая) — буквы редакцией, без шапок прогонов: машиной не проигрывается.
 * v2 — шапка прогона несёт сервис трубопровода; v1 (без него) проигрывается как трубопровод Телеги.
 */
const val TAPE_FORMAT = 2

/**
 * Лента: вход машины, а не пересказ. Буквы и дела — целиком своими сериализаторами,
 * поэтому любую Ленту можно проиграть той же машиной ([replay]) и сверить дела по шагам. Приватность (адреса,
 * имена серверов) — при отдаче наружу ([anonymize]).
 */
private val tapeJson = Json { allowStructuredMapKeys = true; encodeDefaults = true }

/**
 * С чего машина начала прогон: всё, что она получила, кроме первой буквы; service у нажатия и открытия — чей
 * трубопровод (v1 — Телега). У анализа сервиса нет: набор сервисов — в его первой букве Scan.
 */
@Serializable
sealed interface Head {
    @Serializable @SerialName("press") data class Press(val service: ServiceId = Telegram.id) : Head
    /** Открытие: раунды линии, снятые до Xray, машина сворачивает на старте (OpenMachine). */
    @Serializable @SerialName("open")
    data class Open(val known: List<Letter.LineObserved>, val service: ServiceId = Telegram.id) : Head
    /** Анализ подписки: всё, что машина знает, — в букве Scan. */
    @Serializable @SerialName("scan") data object Scan : Head
}

/**
 * Строка Ленты. Строки прогона несут его id: прогоны пишут в одну Ленту одновременно (детектор до Xray досылает
 * подтверждающий раунд, пока идёт нажатие), поэтому соседство строк — не принадлежность.
 */
@Serializable
sealed interface Row {
    /** Шапка прогона. id — случайный: Ленты разных запусков склеиваются в одну выгрузку. */
    @Serializable @SerialName("run") data class Run(val v: Int, val id: Long, val head: Head) : Row
    /** Буква прогона [run] и дела, которые машина на неё дала. */
    @Serializable @SerialName("step") data class Step(val run: Long, val letter: Letter, val deeds: List<Deed>) : Row
    /** Срок актора вышел раньше, чем машина готова: этого входа машина не видит, экран от него зависит. */
    @Serializable @SerialName("closed") data class Closed(val run: Long) : Row
    /** Что край сделал мимо машины (recall, dropExit, ошибки setExit): прогон её не повторяет. */
    @Serializable @SerialName("note") data class Note(val text: String) : Row
}

fun rowLine(row: Row): String = tapeJson.encodeToString(Row.serializer(), row)

/** Строка Ленты v1/v2; не она — исключение сериализатора (прогон ловит его сам). */
fun rowOf(line: String): Row = tapeJson.decodeFromString(Row.serializer(), line)

fun noteLine(text: String): String = rowLine(Row.Note(text))
