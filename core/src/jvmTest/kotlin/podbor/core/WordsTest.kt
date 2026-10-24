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
            Triple(ExitState.Off, "выход не выбран", listOf("Xray выбирает сам — нажми «Подобрать»")),
            Triple(ExitState.Unlisted(ServerId("s042")), "s042", listOf("такого сервера нет в подписке, проверить нечем")),
            Triple(ExitState.Checking(nl), "«NL-7» (gRPC)", listOf("проверяю Телегу…")),
            Triple(ExitState.Works(nl), "«NL-7» (gRPC)", emptyList()),
            Triple(ExitState.Dead(nl, Blame.Whitelist), "«NL-7» (gRPC)", listOf("белые списки: сеть его не пропускает")),
            Triple(ExitState.Dead(nl, Blame.SuspectedWhitelist), "«NL-7» (gRPC)", listOf("похоже на белые списки, подтверждаю")),
            Triple(ExitState.Dead(nl, Blame.NoNetwork), "«NL-7» (gRPC)", listOf("нет сети")),
            Triple(ExitState.Dead(nl, Blame.Server), "«NL-7» (gRPC)", listOf("сеть ни при чём — проблема у сервера")),
            Triple(ExitState.Dead(nl, Blame.Unexplained), "«NL-7» (gRPC)", listOf("сеть непонятная, причину не называю")),
        ) { (state, head, notes) ->
            exitHead(state) shouldBe head
            exitNotes(state) shouldBe notes
        }
    }

    context("выход и переезд сети — по строке на мысль: что с выходом, потом что с переездом") {
        val moved = Relocation.Moved(NetworkMove(OsNetwork.Wifi, OsNetwork.Cellular("MTS", "25001")))
        withData(
            nameFn = { "${it.first}" },
            ExitState.Checking(nl, moved) to listOf("сеть сменилась: Wi-Fi → MTS — проверяю Телегу…"),
            ExitState.Works(nl, moved) to listOf("подобран на Wi-Fi, на MTS тоже работает"),
            ExitState.Dead(nl, Blame.Whitelist, moved) to
                listOf("белые списки: сеть его не пропускает", "подобран на Wi-Fi — нажми, подберу под MTS"),
            ExitState.Works(nl, Relocation.Stayed) to emptyList(),
            ExitState.Dead(nl, Blame.Server, Relocation.Stayed) to listOf("сеть ни при чём — проблема у сервера"),
        ) { (state, notes) -> exitNotes(state) shouldBe notes }
        moveWords(moved.move) shouldBe "сеть сменилась: Wi-Fi → MTS"
    }

    context("переезд — только когда обе сети с ключом и ключи разные") {
        val mts = OsNetwork.Cellular("MTS", "25001")
        withData(
            nameFn = { "${it.first} → ${it.second}" },
            Triple(ChosenOn.Unknown, mts, Relocation.Unknown),
            Triple(ChosenOn.Network(OsNetwork.Wifi), mts, Relocation.Moved(NetworkMove(OsNetwork.Wifi, mts))),
            Triple(ChosenOn.Network(mts), mts.copy(tower = Tower.Known(cellId = 1, earfcn = 1575, rsrpDbm = -103)), Relocation.Stayed),
            Triple(ChosenOn.Network(OsNetwork.Wifi), OsNetwork.None, Relocation.Unknown),
            Triple(ChosenOn.Network(OsNetwork.None), mts, Relocation.Unknown),
        ) { (chosen, now, relocation) -> relocationOf(chosen, now) shouldBe relocation }
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
            NetworkCard.Measuring(OsNetwork.Wifi) to "измеряю Wi-Fi…",
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
