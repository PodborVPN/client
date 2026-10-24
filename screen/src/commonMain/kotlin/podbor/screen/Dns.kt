package podbor.screen

import podbor.core.DnsHealth
import podbor.core.NetworkCard

/** Что строка улики плашки «Сеть» говорит про DNS: ничего (улика линии как была), сломан или починен. */
sealed interface DnsLine {
    data object Quiet : DnsLine
    /** DNS сети сломан, ремонта нет: слова и 🔧 «починить». */
    data class Broken(val text: String) : DnsLine
    /** На этой сети починено: как обошли и 🔧 «вернуть». */
    data class Fixed(val text: String) : DnsLine
}

/**
 * Строка улики и DNS: починено на этой сети ([fixed]) — видно всегда, даже если DNS сети ожил (иначе ремонт не вернуть);
 * сломан — что не откроется; иначе — молчим, строка улики как была.
 */
fun dnsLineOf(card: NetworkCard, fixed: Boolean): DnsLine {
    val dns = when (card) {
        is NetworkCard.Measuring -> DnsHealth.Unknown
        is NetworkCard.Measured -> card.dns
    }
    return when {
        fixed && dns == DnsHealth.Broken -> DnsLine.Fixed("DNS сети не отвечает · починено: Яндекс DNS")
        fixed -> DnsLine.Fixed("российские сайты — через Яндекс DNS")
        dns == DnsHealth.Broken -> DnsLine.Broken("DNS сети не отвечает — российские сайты не откроются")
        else -> DnsLine.Quiet
    }
}
