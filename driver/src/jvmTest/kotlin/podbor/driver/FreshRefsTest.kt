package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.runBlocking
import podbor.core.Handshake
import podbor.core.Lookup
import kotlin.random.Random

/**
 * Свежие эталоны: случайный поддомен двух независимых сервисов, которые отдают адрес из самого имени, — его нет ни в
 * одном кеше DNS. В Ленте — метка без случайной части; соединяемся с Яндекс DNS по его сертификату.
 */
class FreshRefsTest : FunSpec({

    test("два сервиса, метка постоянная, имя каждый раз новое и указывает на Яндекс DNS") {
        val a = freshRefs(Random(1))
        val b = freshRefs(Random(2))
        a.map { it.label } shouldBe listOf("fresh.sslip.io", "fresh.nip.io")
        a.map { it.lookup } shouldBe listOf(Lookup.Fresh, Lookup.Fresh)
        a.map { it.host } shouldNotBe b.map { it.host }
        a[0].host shouldEndWith "-77-88-8-8.sslip.io"
        a[1].host shouldEndWith ".77.88.8.8.nip.io"
        a.map { it.sni } shouldBe listOf("common.dot.dns.yandex.net", "common.dot.dns.yandex.net")
    }

    test("раунд линии: свежие — среди российских, проба несёт Lookup.Fresh, имя в Ленте — метка") {
        val probe = LineProbeImpl(shaker = { Handshake.Ok(10) }, fresh = { freshRefs(Random(3)) })
        val line = runBlocking { probe.observe() }
        line.russia.filter { it.lookup == Lookup.Fresh }.map { it.target } shouldBe listOf("fresh.sslip.io", "fresh.nip.io")
        line.russia.filter { it.lookup == Lookup.Usual }.map { it.target } shouldBe RUSSIA_REFS.map { it.label }
    }
})
