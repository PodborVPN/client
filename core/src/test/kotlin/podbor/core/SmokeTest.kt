package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SmokeTest : FunSpec({
    test("ядро собирается") { (1 + 1) shouldBe 2 }
})
