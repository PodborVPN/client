package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * DNS сети — отдельная ось рядом со сценарием: «не резолвится» бывает только у проб по имени (российские эталоны),
 * пробы по IP (Яндекс DNS, заграница) DNS не нужен — их ответ доказывает, что связь есть.
 */
class DnsTest : FunSpec({

    val dns = Handshake.Unknown("dns")
    val ok = Handshake.Ok(40)
    val silent = Handshake.Silent(Stage.Tls, waitedMs = 1_200)
    fun round(ya: Handshake, vk: Handshake, gu: Handshake, yandexIp: Handshake, far: Handshake) = LineObservation(
        nonEmptyListOf(Sample("ya.ru", ya), Sample("vk.com", vk), Sample("gosuslugi.ru", gu), Sample("dns.yandex", yandexIp)),
        nonEmptyListOf(Sample("one.one.one.one", far), Sample("dns.google", far)),
    )

    test("имена не резолвятся, а по IP связь есть — DNS сети сломан (и при открытой сети, и при белых списках)") {
        dnsOf(round(dns, dns, dns, ok, ok)) shouldBe DnsHealth.Broken
        dnsOf(round(dns, dns, dns, ok, silent)) shouldBe DnsHealth.Broken
    }

    test("сбоев DNS нет — работает, что бы ни было с рукопожатиями") {
        dnsOf(round(ok, ok, ok, ok, ok)) shouldBe DnsHealth.Works
        dnsOf(round(silent, silent, silent, ok, silent)) shouldBe DnsHealth.Works
    }

    test("никто не ответил — не отличить от «нет сети»; один сбой — случайность: не знаем") {
        dnsOf(round(dns, dns, dns, silent, silent)) shouldBe DnsHealth.Unknown
        dnsOf(round(dns, ok, ok, ok, ok)) shouldBe DnsHealth.Unknown
    }

    test("диагноз: DNS — по последнему раунду; раундов нет — не знаем") {
        val broken = round(dns, dns, dns, ok, ok)
        dnsOf(Diagnosis.Pending) shouldBe DnsHealth.Unknown
        dnsOf(Diagnosis.Pending.observe(OsNetwork.Wifi, broken)) shouldBe DnsHealth.Broken
    }
})
