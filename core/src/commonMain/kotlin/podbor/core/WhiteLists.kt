package podbor.core

import arrow.core.Option
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.raise.option

/**
 * Списки белых IP (CIDR v4/v6) и SNI (домен = он и поддомены). Чистый разбор без java.net:
 * InetAddress может ходить в DNS. Строки-мусор пропускаются.
 */
class WhiteLists private constructor(private val nets: List<Net>, private val domains: Set<String>) {

    /** Сеть в 128 битах: IPv4 лежит как ::ffff:a.b.c.d, префикс сдвинут на 96. */
    private class Net(val hi: Long, val lo: Long, val prefix: Int)

    fun hasIp(ip: String): Boolean = parseIp(ip.trim()).fold({ false }) { (hi, lo) -> nets.any { matches(it, hi, lo) } }

    /** host или любой его родитель в списке; регистр не важен. */
    fun hasSni(host: String): Boolean {
        var h = host.trim().trimEnd('.').lowercase()
        while (h.isNotEmpty()) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
        return false
    }

    companion object {
        /**
         * IP-литерал (v4 или v6) в 128 битах: IPv4 лежит как ::ffff:a.b.c.d. None — не IP. Чистый разбор, DNS не трогает.
         */
        fun ipBits(s: String): Option<Pair<Long, Long>> = parseIp(s.trim())

        fun of(cidrs: List<String>, domains: List<String>): WhiteLists =
            WhiteLists(
                cidrs.mapNotNull { parseCidr(it).getOrNull() },
                domains.map { it.trim().trimEnd('.').lowercase() }.filter(String::isNotEmpty).toSet(),
            )

        private fun matches(n: Net, hi: Long, lo: Long): Boolean =
            when {
                n.prefix == 0 -> true
                n.prefix <= 64 -> maskHigh(hi, n.prefix) == maskHigh(n.hi, n.prefix)
                else -> hi == n.hi && maskHigh(lo, n.prefix - 64) == maskHigh(n.lo, n.prefix - 64)
            }

        /** Оставить старшие bits (1..64) бит. */
        private fun maskHigh(v: Long, bits: Int): Long = if (bits >= 64) v else v and (-1L shl (64 - bits))

        private fun parseCidr(line: String): Option<Net> = option {
            val s = line.trim()
            val slash = s.indexOf('/')
            val addr = if (slash < 0) s else s.substring(0, slash)
            val (hi, lo) = parseIp(addr).bind()
            val v4 = !addr.contains(':')
            val max = if (v4) 32 else 128
            val p = if (slash < 0) max else ensureNotNull(s.substring(slash + 1).toIntOrNull())
            ensure(p in 0..max)
            Net(hi, lo, if (v4) p + 96 else p)
        }

        private fun parseIp(s: String): Option<Pair<Long, Long>> =
            if (s.contains(':')) parseV6(s) else parseV4(s).map { 0L to (0xFFFFL shl 32 or it) }

        private fun parseV4(s: String): Option<Long> = option {
            val parts = s.split('.')
            ensure(parts.size == 4)
            parts.fold(0L) { v, p ->
                ensure(p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' })
                val n = p.toInt()
                ensure(n <= 255)
                v shl 8 or n.toLong()
            }
        }

        /** Группы половины v6 (по 16 бит); хвост вида a.b.c.d — две группы. */
        private fun groups(part: String): Option<List<Int>> = option {
            if (part.isEmpty()) return@option emptyList()
            val items = part.split(':')
            items.withIndex().flatMap { (i, g) ->
                if (g.contains('.')) {
                    ensure(i == items.lastIndex)
                    val v4 = parseV4(g).bind()
                    listOf((v4 shr 16).toInt(), (v4 and 0xFFFF).toInt())
                } else {
                    ensure(g.isNotEmpty() && g.length <= 4)
                    listOf(ensureNotNull(g.toIntOrNull(16)))
                }
            }
        }

        private fun parseV6(s: String): Option<Pair<Long, Long>> = option {
            val halves = s.split("::")
            ensure(halves.size <= 2)
            val head = groups(halves[0]).bind()
            val all: List<Int> = if (halves.size == 2) {
                val tail = groups(halves[1]).bind()
                val zeros = 8 - head.size - tail.size
                ensure(zeros >= 1)
                head + List(zeros) { 0 } + tail
            } else head
            ensure(all.size == 8)
            val hi = all.take(4).fold(0L) { v, g -> v shl 16 or g.toLong() }
            val lo = all.drop(4).fold(0L) { v, g -> v shl 16 or g.toLong() }
            hi to lo
        }
    }
}

/** Likely ⟺ вход в белых IP: белые списки операторов — по адресу (у МТС — по CIDR), SNI не условие. */
fun whiteHint(lists: WhiteLists, entryIp: String): WhiteHint =
    if (lists.hasIp(entryIp)) WhiteHint.Likely else WhiteHint.Unlikely

/** SNI сервера в белом списке SNI — отдельный факт карточки и паспорта, на [whiteHint] не влияет; нет SNI — нет. */
fun sniWhite(lists: WhiteLists, sni: String?): Boolean = sni != null && lists.hasSni(sni)
