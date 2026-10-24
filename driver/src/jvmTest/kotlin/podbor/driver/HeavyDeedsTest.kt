package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import podbor.core.Deed
import podbor.core.Endpoint
import podbor.core.Letter
import podbor.core.Memory
import podbor.core.ServerId
import podbor.core.Verdict

/**
 * Тяжёлые дела (вся память сети, весь паспорт) — в Ленте хешем: машина и так выдаст их повтором, а сверить хватает
 * хеша. Лёгкие — как есть: их читают глазами.
 */
class HeavyDeedsTest : FunSpec({

    val letter = Letter.ProbeAnswered(1, ServerId("s001"), Verdict.Unreachable)
    val remember = Deed.Remember(Memory.Empty)
    val probe = Deed.MeasureExit(ServerId("s001"))
    fun blocked(n: Int) = Deed.Remember(Memory.Empty.copy(blocks = (1..n).associate { Endpoint("10.0.0.$it", 443) to it.toLong() }))

    test("лёгкие — как есть, тяжёлые — хешем на своём месте; одно и то же дело — один хеш") {
        val r = recordedOf(listOf(probe, remember, Deed.ProbeLine))
        r.light shouldBe listOf(probe, Deed.ProbeLine)
        r.heavy.single().at shouldBe 1
        r.heavy.single().kind shouldBe "Remember"
        r.heavy.single().sha256.length shouldBe 64
        recordedOf(listOf(Deed.Remember(Memory.Empty))).heavy.single().sha256 shouldBe r.heavy.single().sha256
    }

    test("другая память — другой хеш; другой порядок дел — другая запись: повтор заметит") {
        recordedOf(listOf(blocked(2))) shouldNotBe recordedOf(listOf(blocked(3)))
        recordedOf(listOf(probe, remember)) shouldNotBe recordedOf(listOf(remember, probe))
    }

    test("строка Ленты: тяжёлое — хешем, а не целиком; старая строка (всё целиком) и новая — одно и то же для сверки") {
        val deeds = listOf(probe, blocked(5))
        val new = rowOf(rowLine(stepOf(7, letter, deeds))).shouldBeInstanceOf<Row.Step>()
        new.deeds shouldBe listOf(probe)
        val old = Row.Step(7, letter, deeds)
        old.recorded() shouldBe new.recorded()
        old.recorded() shouldBe recordedOf(deeds)
    }

    test("анонимная Лента: хеш стёрт (по нему перебором узнали бы адреса) — сверяются место и вид, лёгкие — целиком") {
        val line = rowLine(stepOf(7, letter, listOf(probe, blocked(3))))
        val anon = rowOf(anonymize(line).trim()).shouldBeInstanceOf<Row.Step>()
        anon.heavy.single().sha256 shouldBe ""
        anon.recorded().matches(recordedOf(listOf(probe, blocked(9)))) shouldBe true
        anon.recorded().matches(recordedOf(listOf(blocked(9), probe))) shouldBe false
        anon.recorded().matches(recordedOf(listOf(Deed.ProbeLine, blocked(9)))) shouldBe false
        recordedOf(listOf(blocked(3))).matches(recordedOf(listOf(blocked(9)))) shouldBe false
    }

    test("тяжёлое в строке — сотни байт вместо килобайт") {
        val big = blocked(200)
        rowLine(Row.Step(7, letter, listOf(big))).length shouldBeGreaterThan 5_000
        rowLine(stepOf(7, letter, listOf(big))).length shouldBeLessThan 400
    }
})
