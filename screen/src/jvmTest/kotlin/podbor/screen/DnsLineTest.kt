package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.DnsHealth
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.Scenario

/**
 * Строка улики на плашке «Сеть» и DNS: сломан — слова и 🔧; починен на этой сети — «через Яндекс DNS» и 🔧 вернуть;
 * иначе — обычная улика линии. Одна строка — плашка не меняет высоту.
 */
class DnsLineTest : FunSpec({

    fun card(dns: DnsHealth) = NetworkCard.Measured(OsNetwork.Wifi, Scenario.Wifi, "заграница отвечает", dns)

    test("DNS сломан и не починен — говорим, что не откроется, и даём ключ") {
        dnsLineOf(card(DnsHealth.Broken), fixed = false) shouldBe DnsLine.Broken("DNS сети не отвечает — российские сайты не откроются")
    }

    test("починен на этой сети — говорим, как обошли; ремонт виден, даже если DNS сети ожил") {
        dnsLineOf(card(DnsHealth.Broken), fixed = true) shouldBe DnsLine.Fixed("DNS сети не отвечает · починено: Яндекс DNS")
        dnsLineOf(card(DnsHealth.Works), fixed = true) shouldBe DnsLine.Fixed("российские сайты — через Яндекс DNS")
    }

    test("DNS работает или не знаем, ремонта нет — строка улики как была") {
        dnsLineOf(card(DnsHealth.Works), fixed = false) shouldBe DnsLine.Quiet
        dnsLineOf(card(DnsHealth.Unknown), fixed = false) shouldBe DnsLine.Quiet
        dnsLineOf(NetworkCard.Measuring(OsNetwork.Wifi), fixed = false) shouldBe DnsLine.Quiet
    }
})
