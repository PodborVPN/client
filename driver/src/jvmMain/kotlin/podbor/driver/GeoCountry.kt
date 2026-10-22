package podbor.driver

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.google.protobuf.CodedInputStream
import podbor.core.WhiteLists
import java.io.File

/**
 * geoip.dat Xray (protobuf `GeoIPList{GeoIP{country_code, cidr[{ip bytes, prefix}]}}`) → страна IP. Файл читается один раз
 * потоком (целиком в память не берём), .proto не нужен. Записи reverse_match («всё, кроме») пропускаем.
 * Хранение — только примитивные массивы (файл ~17 МБ, сетей сотни тысяч). Диапазоны вложены друг в друга —
 * побеждает самый длинный префикс. Адрес сервера никуда не уходит: всё локально.
 *
 * v4: одна запись = `сеть(32) shl 16 | префикс(6) shl 10 | страна(10)` в отсортированном LongArray.
 * v6: отсортированные по (hi, lo, префикс) массивы hi/lo и мета `префикс shl 16 | страна`.
 */
class GeoCountry private constructor(
    private val countries: List<String>,
    private val v4: LongArray,
    private val v4Prefixes: IntArray,
    private val v6Hi: LongArray,
    private val v6Lo: LongArray,
    private val v6Meta: IntArray,
    private val v6Prefixes: IntArray,
) {
    /** Сколько диапазонов (v4 + v6) загружено. */
    val ranges: Int get() = v4.size + v6Hi.size

    /** Страна как записана в файле ("RU", "NL"); null — адрес не IP или вне всех диапазонов. */
    fun of(ip: String): String? {
        val (hi, lo) = WhiteLists.ipBits(ip) ?: return null
        val index = if (hi == 0L && lo ushr 32 == 0xFFFFL) v4Index(lo and 0xFFFFFFFFL) else v6Index(hi, lo)
        return index?.let(countries::get)
    }

    private fun v4Index(addr: Long): Int? {
        for (p in v4Prefixes) {
            val probe = (addr and maskV4(p) shl 16) or (p.toLong() shl 10)
            val i = lowerBound(v4.size) { v4[it] < probe }
            if (i < v4.size && v4[i] ushr 10 == probe ushr 10) return (v4[i] and COUNTRY_MASK).toInt()
        }
        return null
    }

    private fun v6Index(hi: Long, lo: Long): Int? {
        for (p in v6Prefixes) {
            val (mh, ml) = maskV6(hi, lo, p)
            val i = lowerBound(v6Hi.size) { compare6(v6Hi[it], v6Lo[it], v6Meta[it] ushr 16, mh, ml, p) < 0 }
            if (i < v6Hi.size && compare6(v6Hi[i], v6Lo[i], v6Meta[i] ushr 16, mh, ml, p) == 0) return v6Meta[i] and 0xFFFF
        }
        return null
    }

    /** Первый индекс, где [less] ложно (массив отсортирован: less истинно у префикса). */
    private inline fun lowerBound(size: Int, less: (Int) -> Boolean): Int {
        var l = 0; var h = size
        while (l < h) { val m = (l + h) ushr 1; if (less(m)) l = m + 1 else h = m }
        return l
    }

    /** Растущий LongArray без упаковки. */
    private class Longs {
        var a = LongArray(1024); var n = 0
        fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    }

    companion object {
        fun load(file: File): Either<EngineError, GeoCountry> =
            Either.catch { file.inputStream().buffered(1 shl 16).use { parse(it) } }
                .mapLeft { EngineError.Failed("geoip.dat не прочитан или повреждён: ${it.message}") }
                .flatMap { it }

        private const val COUNTRY_MASK = 0x3FFL

        private fun maskV4(p: Int): Long = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
        private fun maskV6(hi: Long, lo: Long, p: Int): Pair<Long, Long> = when {
            p == 0 -> 0L to 0L
            p <= 64 -> (hi and (-1L shl (64 - p))) to 0L
            else -> hi to (lo and (-1L shl (128 - p)))
        }

        /** Беззнаковое сравнение (hi, lo, префикс). */
        private fun compare6(h: Long, l: Long, p: Int, h2: Long, l2: Long, p2: Int): Int {
            val a = java.lang.Long.compareUnsigned(h, h2)
            if (a != 0) return a
            val b = java.lang.Long.compareUnsigned(l, l2)
            return if (b != 0) b else p.compareTo(p2)
        }

        private fun parse(stream: java.io.InputStream): Either<EngineError, GeoCountry> {
            val countries = ArrayList<String>()
            val v4 = Longs()
            val v6hi = Longs(); val v6lo = Longs(); val v6meta = Longs()
            val input = CodedInputStream.newInstance(stream).also { it.setSizeLimit(Int.MAX_VALUE) }
            while (!input.isAtEnd) {
                val tag = input.readTag()
                if (tag == 0) break
                if (tag != LIST_ENTRY_TAG) { input.skipField(tag); continue }
                val limit = input.pushLimit(input.readRawVarint32())
                var code = ""
                var reverse = false
                val cidrs = ArrayList<Pair<ByteArray, Int>>()
                while (!input.isAtEnd) {
                    val t = input.readTag()
                    if (t == 0) break
                    when (t) {
                        CODE_TAG -> code = input.readString()
                        CIDR_TAG -> cidrs += readCidr(input)
                        REVERSE_TAG -> reverse = input.readBool()
                        else -> input.skipField(t)
                    }
                }
                input.popLimit(limit)
                if (reverse) continue
                val country = countries.size
                if (country > COUNTRY_MASK) return EngineError.Failed("geoip.dat: слишком много стран").left()
                countries += code
                for ((ip, prefix) in cidrs) {
                    when (ip.size) {
                        4 -> {
                            if (prefix !in 0..32) return EngineError.Failed("geoip.dat: префикс $prefix для v4").left()
                            val addr = ip.fold(0L) { a, b -> a shl 8 or (b.toLong() and 0xFF) } and maskV4(prefix)
                            v4.add((addr shl 16) or (prefix.toLong() shl 10) or country.toLong())
                        }
                        16 -> {
                            if (prefix !in 0..128) return EngineError.Failed("geoip.dat: префикс $prefix для v6").left()
                            fun word(from: Int) = (from until from + 8).fold(0L) { a, i -> a shl 8 or (ip[i].toLong() and 0xFF) }
                            val (mh, ml) = maskV6(word(0), word(8), prefix)
                            v6hi.add(mh); v6lo.add(ml); v6meta.add(((prefix shl 16) or country).toLong())
                        }
                        else -> return EngineError.Failed("geoip.dat: адрес в ${ip.size} байт").left()
                    }
                }
            }
            if (countries.isEmpty()) return EngineError.Failed("geoip.dat: нет стран").left()
            val sorted4 = v4.a.copyOf(v4.n).also { it.sort() }
            val prefixes4 = sorted4.map { ((it ushr 10) and 0x3F).toInt() }.toSet().sortedDescending().toIntArray()
            // v6 мало (десятки тысяч): индексы упорядочиваем временно, хранятся лишь примитивные массивы.
            val order = (0 until v6hi.n).sortedWith { x, y ->
                compare6(v6hi.a[x], v6lo.a[x], (v6meta.a[x] ushr 16).toInt(), v6hi.a[y], v6lo.a[y], (v6meta.a[y] ushr 16).toInt())
            }
            val hi = LongArray(order.size) { v6hi.a[order[it]] }
            val lo = LongArray(order.size) { v6lo.a[order[it]] }
            val meta = IntArray(order.size) { v6meta.a[order[it]].toInt() }
            val prefixes6 = meta.map { it ushr 16 }.toSet().sortedDescending().toIntArray()
            return GeoCountry(countries, sorted4, prefixes4, hi, lo, meta, prefixes6).right()
        }

        private fun readCidr(input: CodedInputStream): Pair<ByteArray, Int> {
            val limit = input.pushLimit(input.readRawVarint32())
            var ip = ByteArray(0)
            var prefix = 0
            while (!input.isAtEnd) {
                val t = input.readTag()
                if (t == 0) break
                when (t) {
                    IP_TAG -> ip = input.readByteArray()
                    PREFIX_TAG -> prefix = input.readUInt32()
                    else -> input.skipField(t)
                }
            }
            input.popLimit(limit)
            return ip to prefix
        }

        /** Тег = номер поля << 3 | тип провода (2 = LEN, 0 = varint). */
        private const val LIST_ENTRY_TAG = 1 shl 3 or 2
        private const val CODE_TAG = 1 shl 3 or 2
        private const val CIDR_TAG = 2 shl 3 or 2
        private const val REVERSE_TAG = 3 shl 3
        private const val IP_TAG = 1 shl 3 or 2
        private const val PREFIX_TAG = 2 shl 3
    }
}
