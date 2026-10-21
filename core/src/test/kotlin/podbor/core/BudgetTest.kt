package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BudgetTest : FunSpec({

    val hetzner = Entry.Foreign("NL")

    test("20 серверов на одном IP:порт → стартует 1: проба кандидата — уже 3 рукопожатия (DC1–DC3)") {
        val queue = (1..20).map { server("s%03d".format(it), hetzner, host = "10.0.0.1", port = 8443) }
        pickStartable(queue, inFlight = emptyList()).size shouldBe 1
    }

    test("две записи с одним Endpoint и разными id считаются вместе") {
        val e = { tag: String -> server(tag, hetzner, host = "10.0.0.1", port = 443) }
        pickStartable(queue = listOf(e("s003"), e("s004")), inFlight = listOf(e("s001"))).map { it.id.tag } shouldBe
            emptyList()
    }

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
