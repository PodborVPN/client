package podbor.driver

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import podbor.core.HistoryEvent
import podbor.core.Instagram
import podbor.core.Mood
import podbor.core.OsNetwork
import podbor.core.Reach
import podbor.core.ServerId
import podbor.core.Telegram

/** История сервиса в шторке — свёртка Ленты по прогонам этого сервиса: те же слова, что на экране, свежие сверху. */
class HistoryTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    fun rows(w: World) = w.tape.map(::rowOf)

    test("частичное применение и отмена воспроизводятся и видны в истории затронутых сервисов") {
        val first = ServerId("s001")
        val spare = ServerId("s002")
        val tg = podbor.core.RecoveryRoute.Service(Telegram.id)
        val ig = podbor.core.RecoveryRoute.Service(Instagram.id)
        val letters = listOf(
            podbor.core.RecoveryLetter.Observed(1, podbor.core.RecoveryEvidence.ExitFailed(first), mapOf(tg to first, ig to first)),
            podbor.core.RecoveryLetter.LineChecked(1, podbor.core.RecoveryLine.Reachable),
            podbor.core.RecoveryLetter.Candidates(1, mapOf(tg to spare, ig to spare)),
            podbor.core.RecoveryLetter.Invalidated,
        )
        var state: podbor.core.RecoveryState = podbor.core.RecoveryState.Watching
        val steps = letters.map { letter ->
            val (next, deeds) = podbor.core.recoveryStep(state, letter)
            state = next
            RecoveryStep(letter, deeds)
        }
        val text = rowLine(Row.Recovery(100, steps, mapOf(tg to podbor.core.RecoveryApplied.Applied)))
        replay(text).single().outcome.shouldBeInstanceOf<Replayed.Converged>()
        val rows = listOf(rowOf(text))
        historyOf(rows, Telegram.id, 8).single().mood shouldBe Mood.Good
        historyOf(rows, Instagram.id, 8).single().text shouldBe "автоподбор · попытка отменена"
    }

    test("автоподбор без интернета виден в истории каждого сервиса и воспроизводится") {
        val tape = mutableListOf<String>()
        detectLine(
            LineProbe { line(Reach.Down, Reach.Down) },
            Clock { 100L },
            { tape += it },
            mts,
            setOf(Telegram, Instagram),
            recovery = true,
        )
        val rows = tape.map(::rowOf)
        val expected = listOf(HistoryEvent(100L, Mood.Info, "MTS · нет интернета, автоподбор отложен"))
        historyOf(rows, Telegram.id, 8) shouldBe expected
        historyOf(rows, Instagram.id, 8) shouldBe expected
        replay(tape.joinToString("\n")).single().outcome.shouldBeInstanceOf<Replayed.Converged>()
    }

    test("нажатие нашло, открытие проверило — два события, свежее сверху, с сетью и словами экрана") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = setOf(20000))
        w.pressNow("подписка", mts, currentExit = null).shouldBeInstanceOf<Either.Right<*>>()
        w.openNow("подписка", OsNetwork.Wifi, currentExit = ServerId("s001")).shouldBeInstanceOf<Either.Right<*>>()
        val h = historyOf(rows(w), Telegram.id, limit = 8)
        h.map { it.mood } shouldBe listOf(Mood.Good, Mood.Good)
        h[0].text shouldBe "Wi-Fi · «Сервер 0» (tcp) работает"
        h[1].text.startsWith("MTS · подобрал «Сервер 0» (tcp)") shouldBe true
        (h[0].atMs >= h[1].atMs) shouldBe true
    }

    test("не нашёл и мёртв — плохие события со своими причинами; чужой сервис — не наш") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = emptySet())
        w.pressNow("подписка", mts, currentExit = null)
        w.openNow("подписка", mts, currentExit = ServerId("s001"))
        val h = historyOf(rows(w), Telegram.id, limit = 8)
        h.map { it.mood } shouldBe listOf(Mood.Bad, Mood.Bad)
        h[0].text shouldBe "MTS · «Сервер 0» (tcp): сеть ни при чём — проблема у сервера"
        h[1].text.startsWith("MTS · не нашёл: ") shouldBe true
        historyOf(rows(w), Instagram.id, limit = 8) shouldBe emptyList<HistoryEvent>()
    }

    test("сколько влезает — столько и отдаём, свежие") {
        val w = World(ips = listOf("10.0.0.1"), ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = setOf(20000))
        repeat(3) { w.openNow("подписка", mts, currentExit = ServerId("s001")) }
        val all = historyOf(rows(w), Telegram.id, limit = 8)
        all.size shouldBe 3
        historyOf(rows(w), Telegram.id, limit = 2) shouldBe all.take(2)
    }
})
