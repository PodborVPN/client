package podbor.driver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kotlincrypto.hash.sha2.SHA256
import podbor.core.Deed
import podbor.core.Letter
import podbor.core.PulseSample
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
    /**
     * Буква прогона [run] и дела, которые машина на неё дала: лёгкие — в [deeds] как есть, тяжёлые — в [heavy] хешем
     * ([recordedOf]). Ленты до хешей несут все дела в [deeds] целиком — сверка их понимает ([recorded]).
     */
    @Serializable @SerialName("step")
    data class Step(val run: Long, val letter: Letter, val deeds: List<Deed>, val heavy: List<Digest> = emptyList()) : Row
    /** Срок актора вышел раньше, чем машина готова: этого входа машина не видит, экран от него зависит. */
    @Serializable @SerialName("closed") data class Closed(val run: Long) : Row
    /** Что край сделал мимо машины (recall, dropExit, ошибки setExit): прогон её не повторяет. */
    @Serializable @SerialName("note") data class Note(val text: String) : Row
    /** Отсчёт счётчиков Xray сессии (пульс): ничей прогон, машинами не проигрывается — сырьё знания. */
    @Serializable @SerialName("pulse") data class Pulse(val sample: PulseSample) : Row
    /** Старая Лента удалена по потолку ([TAPE_CAP_BYTES]): свёртки по истории знают о дыре, а не врут. */
    @Serializable @SerialName("lost") data class Lost(val lost: podbor.driver.Lost) : Row
}

/**
 * Тяжёлое дело в Ленте: [at] — его место среди дел шага, [kind] — вид, [sha256] — хеш его JSON; пусто — стёрт
 * анонимизацией ([anonymize]): по хешу памяти перебором узнали бы адреса.
 */
@Serializable
data class Digest(val at: Int, val kind: String, val sha256: String)

/** Дела шага, как их хранит и сверяет Лента: лёгкие — как есть, тяжёлые — хешем на своих местах. */
data class Recorded(val light: List<Deed>, val heavy: List<Digest>) {
    /** Сверка с делами повтора: лёгкие — целиком, тяжёлые — место и вид, хеш — если не стёрт. */
    fun matches(replayed: Recorded): Boolean =
        light == replayed.light && heavy.size == replayed.heavy.size &&
            heavy.zip(replayed.heavy).all { (r, p) -> r.at == p.at && r.kind == p.kind && (r.sha256.isEmpty() || r.sha256 == p.sha256) }
}

/**
 * Вся память сети и весь паспорт — тяжёлые: машина отдаёт их на каждом шаге, а сверить хватает хеша (анализ подписки
 * писал сотни КБ памяти в Ленту). Остальные дела — по десятку байт, их читают глазами.
 */
fun recordedOf(deeds: List<Deed>): Recorded {
    val heavy = deeds.withIndex().mapNotNull { (i, d) ->
        when (d) {
            is Deed.Remember -> Digest(i, "Remember", sha256Of(d))
            is Deed.SavePassport -> Digest(i, "SavePassport", sha256Of(d))
            Deed.ProbeLine, is Deed.Probe, is Deed.MakeExit, is Deed.MeasureSpeed, is Deed.MeasureExit -> null
        }
    }
    val at = heavy.map { it.at }.toSet()
    return Recorded(deeds.filterIndexed { i, _ -> i !in at }, heavy)
}

/** Строка шага Ленты: дела — в виде [recordedOf]. */
fun stepOf(run: Long, letter: Letter, deeds: List<Deed>): Row.Step =
    recordedOf(deeds).let { r -> Row.Step(run, letter, r.light, r.heavy) }

/** Дела строки для сверки — одинаково для Лент с хешами и Лент, где все дела целиком. */
fun Row.Step.recorded(): Recorded {
    val inline = recordedOf(deeds)
    return when {
        heavy.isEmpty() -> inline
        else -> Recorded(inline.light, heavy)
    }
}

private fun sha256Of(d: Deed): String =
    SHA256().digest(tapeJson.encodeToString(Deed.serializer(), d).encodeToByteArray())
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun rowLine(row: Row): String = tapeJson.encodeToString(Row.serializer(), row)

/** Строка Ленты v1/v2; не она — исключение сериализатора (прогон ловит его сам). */
fun rowOf(line: String): Row = tapeJson.decodeFromString(Row.serializer(), line)

fun noteLine(text: String): String = rowLine(Row.Note(text))
