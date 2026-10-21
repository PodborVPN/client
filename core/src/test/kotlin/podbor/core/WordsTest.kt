package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/** Слова экрана — одни для CLI и APK: каждый вариант выхода, вины и карточки сети. */
class WordsTest : FunSpec({

    val nl = ExitRef("NL-7", Transport.Grpc)

    context("exitWords — каждый вариант ExitState и Blame") {
        withData(
            nameFn = { it.second },
            ExitState.Off to "выход не выбран — Xray выбирает сам, нажми «Подобрать»",
            ExitState.Unlisted(ServerId("s042")) to "s042 — такого сервера нет в подписке, проверить нечем",
            ExitState.Checking(nl) to "«NL-7» (gRPC) · проверяю Телегу…",
            ExitState.Works(nl) to "«NL-7» (gRPC) · Телега ✓",
            ExitState.Dead(nl, Blame.Whitelist) to "«NL-7» (gRPC) · Телега ✗ · белые списки: сеть его не пропускает",
            ExitState.Dead(nl, Blame.SuspectedWhitelist) to "«NL-7» (gRPC) · Телега ✗ · похоже на белые списки, подтверждаю",
            ExitState.Dead(nl, Blame.NoNetwork) to "«NL-7» (gRPC) · Телега ✗ · нет сети",
            ExitState.Dead(nl, Blame.Server) to "«NL-7» (gRPC) · Телега ✗ · сеть ни при чём — проблема у сервера",
            ExitState.Dead(nl, Blame.Unexplained) to "«NL-7» (gRPC) · Телега ✗ · сеть непонятная, причину не называю",
        ) { (state, words) -> exitWords(state) shouldBe words }
    }

    context("голова и пояснение выхода — для двух строк экрана") {
        withData(
            nameFn = { "${it.first}" },
            Triple(ExitState.Off, "выход не выбран", "Xray выбирает сам — нажми «Подобрать»"),
            Triple(ExitState.Unlisted(ServerId("s042")), "s042", "такого сервера нет в подписке, проверить нечем"),
            Triple(ExitState.Checking(nl), "«NL-7» (gRPC)", "проверяю Телегу…"),
            Triple(ExitState.Works(nl), "«NL-7» (gRPC)", null),
            Triple(ExitState.Dead(nl, Blame.Whitelist), "«NL-7» (gRPC)", "белые списки: сеть его не пропускает"),
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
    val mtsTower = OsNetwork.Cellular("MTS", "25001", Tower.Known(cellId = 1, earfcn = 1575, rsrpDbm = -100))

    context("networkWords — каждая карточка сети") {
        withData(
            nameFn = { it.second },
            NetworkCard.Measuring(OsNetwork.None) to "измеряю сеть…",
            NetworkCard.Measuring(mts) to "измеряю сеть… · MTS",
            NetworkCard.Measuring(mtsTower) to "измеряю сеть… · MTS · вышка 1575 · −100 дБм",
            NetworkCard.Measured(OsNetwork.Wifi, Scenario.Wifi, "заграница отвечает") to "Wi-Fi · заграница отвечает",
            NetworkCard.Measured(mts, Scenario.MobileWhitelist, "заграница не отвечает, РФ работает") to
                "Белые списки · MTS · заграница не отвечает, РФ работает",
            NetworkCard.Measured(mts, Scenario.SuspectWhitelist, "заграница не отвечает, РФ работает — проверяю ещё раз") to
                "Похоже на белые списки · MTS · заграница не отвечает, РФ работает — проверяю ещё раз",
            NetworkCard.Measured(mts, Scenario.MobileOpen, "заграница отвечает") to "Сеть чистая · MTS · заграница отвечает",
            NetworkCard.Measured(OsNetwork.None, Scenario.Offline, "ни РФ, ни заграница не отвечают") to
                "Нет сети · ни РФ, ни заграница не отвечают",
            NetworkCard.Measured(mts, Scenario.Unclear, "ответы неполные: РФ 1 из 3, заграница 0 из 3") to
                "Сеть непонятная · MTS · ответы неполные: РФ 1 из 3, заграница 0 из 3",
        ) { (card, words) -> networkWords(card) shouldBe words }
    }

    test("networkTitle: пока линия молчит — «измеряю сеть…», потом сценарий") {
        networkTitle(NetworkCard.Measuring(mts)) shouldBe "измеряю сеть…"
        networkTitle(NetworkCard.Measured(mts, Scenario.MobileWhitelist, "")) shouldBe "Белые списки"
    }

    test("foundWords — найденный выход и покрытие, как в CLI") {
        foundWords(ResultCard.Found("NL-7", Transport.Xhttp, probedAtMs = 0, coverage = "проверено 2 из 11 входов РФ")) shouldBe
            "Телега ✓ через «NL-7» (XHTTP) · проверено 2 из 11 входов РФ"
    }
})
