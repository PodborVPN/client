package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/** Слова экрана: каждый вариант выхода, вины и карточки сети. */
class WordsTest : FunSpec({

    val nl = ExitRef("NL-7", Transport.Grpc)

    context("голова и пояснение выхода — для двух строк экрана, каждый вариант ExitState и Blame") {
        withData(
            nameFn = { "${it.first}" },
            Triple(ExitState.Off, "выход не выбран", "Xray выбирает сам — нажми «Подобрать»"),
            Triple(ExitState.Unlisted(ServerId("s042")), "s042", "такого сервера нет в подписке, проверить нечем"),
            Triple(ExitState.Checking(nl), "«NL-7» (gRPC)", "проверяю Телегу…"),
            Triple(ExitState.Works(nl), "«NL-7» (gRPC)", null),
            Triple(ExitState.Dead(nl, Blame.Whitelist), "«NL-7» (gRPC)", "белые списки: сеть его не пропускает"),
            Triple(ExitState.Dead(nl, Blame.SuspectedWhitelist), "«NL-7» (gRPC)", "похоже на белые списки, подтверждаю"),
            Triple(ExitState.Dead(nl, Blame.NoNetwork), "«NL-7» (gRPC)", "нет сети"),
            Triple(ExitState.Dead(nl, Blame.Server), "«NL-7» (gRPC)", "сеть ни при чём — проблема у сервера"),
            Triple(ExitState.Dead(nl, Blame.Unexplained), "«NL-7» (gRPC)", "сеть непонятная, причину не называю"),
        ) { (state, head, note) ->
            exitHead(state) shouldBe head
            exitNote(state) shouldBe note
        }
    }

    context("транспорт не повторяется, если он уже в имени сервера (без учёта регистра, целым словом)") {
        withData(
            nameFn = { it.first },
            "Sweden-1vl(gRPC)" to "«Sweden-1vl(gRPC)»",
            "NL grpc 2" to "«NL grpc 2»",
            "NL-7" to "«NL-7» (gRPC)",
            "agrpcx" to "«agrpcx» (gRPC)",
        ) { (name, words) -> refWords(name, Transport.Grpc) shouldBe words }
        refWords("News-1", Transport.Ws) shouldBe "«News-1» (WS)"
        refWords("DE ws", Transport.Ws) shouldBe "«DE ws»"
    }

    context("транспорт словами") {
        withData(
            Transport.RealityVision to "Reality",
            Transport.Xhttp to "XHTTP",
            Transport.Grpc to "gRPC",
            Transport.Ws to "WS",
            Transport.Other("hysteria2") to "hysteria2",
        ) { (t, words) -> transportWords(t) shouldBe words }
    }

    val mts = OsNetwork.Cellular("MTS", "25001")

    context("заголовок карточки сети: пока линия молчит — «измеряю сеть…», потом сценарий") {
        withData(
            nameFn = { it.second },
            NetworkCard.Measuring(mts) to "измеряю сеть…",
            NetworkCard.Measured(mts, Scenario.MobileWhitelist, "") to "Белые списки",
            NetworkCard.Measured(mts, Scenario.SuspectWhitelist, "") to "Похоже на белые списки",
            NetworkCard.Measured(mts, Scenario.MobileOpen, "") to "Сеть чистая",
            NetworkCard.Measured(OsNetwork.Wifi, Scenario.Wifi, "") to "Wi-Fi",
            NetworkCard.Measured(OsNetwork.None, Scenario.Offline, "") to "Нет сети",
            NetworkCard.Measured(mts, Scenario.Unclear, "") to "Сеть непонятная",
        ) { (card, title) -> networkTitle(card) shouldBe title }
    }

    test("оператор на карточке: сотовая — имя оператора, Wi-Fi — «Wi-Fi», без сети — ничего") {
        operatorOf(mts) shouldBe "MTS"
        operatorOf(OsNetwork.Wifi) shouldBe "Wi-Fi"
        operatorOf(OsNetwork.None) shouldBe null
    }
})
