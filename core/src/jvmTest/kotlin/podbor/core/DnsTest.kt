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

    /** Раунд со свежими именами (случайный поддомен — его нет в кеше DNS): решают они, кешированные имена — нет. */
    fun withFresh(names: Handshake, fresh1: Handshake, fresh2: Handshake, yandexIp: Handshake) = LineObservation(
        nonEmptyListOf(
            Sample("ya.ru", names), Sample("vk.com", names), Sample("dns.yandex", yandexIp),
            Sample("fresh.sslip.io", fresh1, lookup = Lookup.Fresh), Sample("fresh.nip.io", fresh2, lookup = Lookup.Fresh),
        ),
        nonEmptyListOf(Sample("one.one.one.one", ok)),
    )

    test("свежие имена решают: кеш отвечает за ya.ru, а случайный поддомен не резолвится — DNS сломан") {
        dnsOf(withFresh(names = ok, fresh1 = dns, fresh2 = dns, yandexIp = ok)) shouldBe DnsHealth.Broken
    }

    test("свежие резолвятся — работает; не резолвится один сервис из двух — не знаем (лёг сервис, а не DNS)") {
        dnsOf(withFresh(names = ok, fresh1 = ok, fresh2 = ok, yandexIp = ok)) shouldBe DnsHealth.Works
        dnsOf(withFresh(names = ok, fresh1 = dns, fresh2 = ok, yandexIp = ok)) shouldBe DnsHealth.Unknown
        // Связи по IP нет — сломанный DNS не отличить от «нет сети».
        dnsOf(withFresh(names = dns, fresh1 = dns, fresh2 = dns, yandexIp = silent).copy(abroad = nonEmptyListOf(Sample("x", silent)))) shouldBe
            DnsHealth.Unknown
    }

    test("карточка сети несёт DNS по диагнозу — экран скажет и предложит ремонт") {
        val opened = Letter.Opened(0, OsNetwork.Wifi, Vpn.Off)
        val card = render(listOf(opened, Letter.LineObserved(100, round(dns, dns, dns, ok, ok)))).network
        (card as NetworkCard.Measured).dns shouldBe DnsHealth.Broken
        (render(listOf(opened, Letter.LineObserved(100, round(ok, ok, ok, ok, ok)))).network as NetworkCard.Measured).dns shouldBe DnsHealth.Works
    }

    test("диагноз: DNS — по последнему раунду; раундов нет — не знаем") {
        val broken = round(dns, dns, dns, ok, ok)
        dnsOf(Diagnosis.Pending) shouldBe DnsHealth.Unknown
        dnsOf(Diagnosis.Pending.observe(OsNetwork.Wifi, broken)) shouldBe DnsHealth.Broken
    }
})
