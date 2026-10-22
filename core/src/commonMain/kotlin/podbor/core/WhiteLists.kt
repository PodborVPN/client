package podbor.core

/**
 * Списки белых IP (CIDR v4/v6) и SNI (домен = он и поддомены). Чистый разбор без java.net:
 * InetAddress может ходить в DNS. Строки-мусор пропускаются.
 */
class WhiteLists private constructor(private val nets: List<Net>, private val domains: Set<String>) {

    /** Сеть в 128 битах: IPv4 лежит как ::ffff:a.b.c.d, префикс сдвинут на 96. */
    private class Net(val hi: Long, val lo: Long, val prefix: Int)

    fun hasIp(ip: String): Boolean {
        val (hi, lo) = parseIp(ip.trim()) ?: return false
        return nets.any { matches(it, hi, lo) }
    }

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
         * IP-литерал (v4 или v6) в 128 битах: IPv4 лежит как ::ffff:a.b.c.d. null — не IP. Чистый разбор, DNS не трогает.
         */
        fun ipBits(s: String): Pair<Long, Long>? = parseIp(s.trim())

        fun of(cidrs: List<String>, domains: List<String>): WhiteLists =
            WhiteLists(
                cidrs.mapNotNull(::parseCidr),
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

        private fun parseCidr(line: String): Net? {
            val s = line.trim()
            val slash = s.indexOf('/')
            val addr = if (slash < 0) s else s.substring(0, slash)
            val (hi, lo) = parseIp(addr) ?: return null
            val v4 = !addr.contains(':')
            val max = if (v4) 32 else 128
            val p = if (slash < 0) max else s.substring(slash + 1).toIntOrNull() ?: return null
            if (p !in 0..max) return null
            return Net(hi, lo, if (v4) p + 96 else p)
        }

        private fun parseIp(s: String): Pair<Long, Long>? =
            if (s.contains(':')) parseV6(s) else parseV4(s)?.let { 0L to (0xFFFFL shl 32 or it) }

        private fun parseV4(s: String): Long? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            var v = 0L
            for (p in parts) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                val n = p.toInt()
                if (n > 255) return null
                v = v shl 8 or n.toLong()
            }
            return v
        }

        private fun parseV6(s: String): Pair<Long, Long>? {
            val halves = s.split("::")
            if (halves.size > 2) return null
            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val out = ArrayList<Int>()
                val items = part.split(':')
                for ((i, g) in items.withIndex()) {
                    if (g.contains('.')) { // хвост вида a.b.c.d
                        if (i != items.lastIndex) return null
                        val v4 = parseV4(g) ?: return null
                        out += (v4 shr 16).toInt(); out += (v4 and 0xFFFF).toInt()
                    } else {
                        if (g.isEmpty() || g.length > 4) return null
                        out += g.toIntOrNull(16) ?: return null
                    }
                }
                return out
            }
            val head = groups(halves[0]) ?: return null
            val all: List<Int> = if (halves.size == 2) {
                val tail = groups(halves[1]) ?: return null
                val zeros = 8 - head.size - tail.size
                if (zeros < 1) return null
                head + List(zeros) { 0 } + tail
            } else head
            if (all.size != 8) return null
            var hi = 0L
            var lo = 0L
            for (i in 0 until 4) hi = hi shl 16 or all[i].toLong()
            for (i in 4 until 8) lo = lo shl 16 or all[i].toLong()
            return hi to lo
        }
    }
}

/** Likely ⟺ вход в белых IP: белые списки операторов — по адресу (у МТС — по CIDR), SNI не условие. */
fun whiteHint(lists: WhiteLists, entryIp: String): WhiteHint =
    if (lists.hasIp(entryIp)) WhiteHint.Likely else WhiteHint.Unlikely

/** SNI сервера в белом списке SNI — отдельный факт карточки и паспорта, на [whiteHint] не влияет; нет SNI — нет. */
fun sniWhite(lists: WhiteLists, sni: String?): Boolean = sni != null && lists.hasSni(sni)
