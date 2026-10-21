package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SmokeTest : FunSpec({
    test("край собирается и видит ядро") { podbor.core.Reach.Up.name shouldBe "Up" }
})
