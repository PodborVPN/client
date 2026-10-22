package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PluralTest : FunSpec({
    test("склонение по числу") {
        listOf(0, 1, 2, 5, 11, 12, 21, 22, 25, 111).map { serversCount(it) } shouldBe listOf(
            "0 серверов", "1 сервер", "2 сервера", "5 серверов", "11 серверов", "12 серверов",
            "21 сервер", "22 сервера", "25 серверов", "111 серверов",
        )
    }
})
