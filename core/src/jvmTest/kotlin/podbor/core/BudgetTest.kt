package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BudgetTest : FunSpec({

    val hetzner = Entry.Foreign("NL")

    test("5 разных IP с одним входом → стартуют все 5: лимит только по IP:порт") {
        val queue = (1..5).map { server("s%03d".format(it), hetzner, host = "10.0.0.$it") }
        pickStartable(queue, emptyList()).size shouldBe 5
    }

    test("всего не больше 6, порядок очереди сохраняется") {
        val queue = (1..10).map { server("s%03d".format(it), Entry.Foreign("NL"), host = "10.0.1.$it") }
        pickStartable(queue, emptyList()).map { it.id.tag } shouldBe (1..6).map { "s%03d".format(it) }
    }

    test("в полёте уже 6 → не стартует никто") {
        val inFlight = (1..6).map { server("s%03d".format(it), Entry.Foreign("NL"), host = "10.0.3.$it") }
        pickStartable(listOf(server("s099", Entry.Foreign("NL"), host = "10.0.4.1")), inFlight).size shouldBe 0
    }
})
