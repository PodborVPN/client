package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExitFilterTest : FunSpec({
    test("без исключений допустим и неизвестный выход") {
        ExitFilter().allows(Exit.Unknown) shouldBe true
        ExitFilter().allows(Exit.At("203.0.113.1", "?")) shouldBe true
    }
    test("исключение относится к стране конечного выхода") {
        val filter = ExitFilter(setOf("RU", "BY"))
        filter.allows(Exit.At("203.0.113.1", "RU")) shouldBe false
        filter.allows(Exit.At("203.0.113.2", "NL")) shouldBe true
        filter.allows(Exit.At("203.0.113.3", "BY")) shouldBe false
    }
    test("с ограничением неизвестный выход недопустим") {
        val filter = ExitFilter(setOf("RU"))
        filter.allows(Exit.Unknown) shouldBe false
        filter.allows(Exit.At("203.0.113.1", "?")) shouldBe false
        filter.allows(Exit.At("203.0.113.1", "")) shouldBe false
    }
})
