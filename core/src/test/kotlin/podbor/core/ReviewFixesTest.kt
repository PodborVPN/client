package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Найденные ошибки: каждая — тест, который падал до исправления. */
class ReviewFixesTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)
    /** Белые списки — два раунда «похоже» подряд; один — только подозрение. */
    val confirmed = arrayOf<Letter>(Letter.LineObserved(10, whitelist), Letter.LineObserved(20, whitelist))

    fun pressed(servers: List<Server>, exit: ServerId? = null, memory: Memory = Memory.Empty) =
        Letter.Pressed(atMs = 0, os = mts, servers = servers, currentExit = exit, memory = memory)

    test("двойник по IP:порт ещё в полёте после TlsSilence → InProgress, а не красная") {
        val a = server("s001", Entry.Ru, host = "1.1.1.1", port = 443)
        val b = server("s002", Entry.Ru, host = "1.1.1.1", port = 443)
        val letters = listOf(pressed(listOf(a, b)), *confirmed, Letter.ProbeAnswered(100, a.id, Verdict.TlsSilence))
        explain(letters).outcome shouldBe Outcome.InProgress
    }

    test("Fits от сервера, которого машина не запускала, не делает Found") {
        val ru = server("s001", Entry.Ru)
        val foreign = server("s002", Entry.Foreign("NL"))
        val letters = listOf(pressed(listOf(ru, foreign)), *confirmed, Letter.ProbeAnswered(50, foreign.id, Verdict.TelegramDc(5)))
        explain(letters).outcome shouldBe Outcome.InProgress
    }

    test("память сети переживает перестановку подписки") {
        val old = listOf(server("s001", Entry.Ru, host = "10.0.0.1"), server("s002", Entry.Ru, host = "10.0.0.2"))
        val key = MemoryKey("MTS", Scenario.MobileWhitelist)
        val memory = Memory.Empty.rememberSuccess(key, old[1], atMs = 0)
        // провайдер добавил запись в начало: теги сдвинулись
        val reordered = listOf(
            server("s001", Entry.Ru, host = "10.0.0.9"),
            old[0].copy(id = ServerId("s002")),
            old[1].copy(id = ServerId("s003")),
        )
        plan(Scenario.MobileWhitelist, reordered, memory, key, nowMs = 1).queue.first().endpoint.host shouldBe "10.0.0.2"
    }

    test("текущий выход прошёл до линии → зелёная и честный охват, не «1 из 0»") {
        val s = server("s001", Entry.Ru)
        val screen = render(listOf(pressed(listOf(s), exit = s.id), Letter.ProbeAnswered(300, s.id, Verdict.TelegramDc(3))))
        screen.button shouldBe Button.Green
        screen.result.shouldBeInstanceOf<ResultCard.Found>().coverage shouldBe "текущий выход работает"
    }

})
