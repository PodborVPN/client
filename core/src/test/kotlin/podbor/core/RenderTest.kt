package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RenderTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val whitelist = line(Reach.Up, Reach.Down)
    /** Белые списки — два раунда «похоже» подряд; один — только подозрение. */
    val confirmed = arrayOf<Letter>(Letter.LineObserved(10, whitelist), Letter.LineObserved(20, whitelist))

    fun pressed(servers: List<Server>) = Letter.Pressed(atMs = 0, os = mts, servers = servers, currentExit = null, memory = Memory.Empty)
    fun answer(tag: String, v: Verdict, at: Long = 100) = Letter.ProbeAnswered(at, ServerId(tag), v)

    test("секундомер: идёт от нажатия, пока ищет; замирает на ответе, давшем «работает»") {
        val servers = listOf(server("s001", Entry.Ru), server("s002", Entry.Ru))
        val going = listOf(pressed(servers), *confirmed)
        render(going).stopwatch shouldBe Stopwatch.Running(startedAtMs = 0)
        val found = going + answer("s001", Verdict.Unreachable, at = 300) + answer("s002", Verdict.TelegramDc(3), at = 840)
        render(found).stopwatch shouldBe Stopwatch.Stopped(startedAtMs = 0, stoppedAtMs = 840)
        // Поздний раунд линии после итога время не двигает.
        render(found + Letter.LineObserved(2_000, whitelist)).stopwatch shouldBe Stopwatch.Stopped(0, 840)
    }

    test("секундомер: «не найдено» — на письме, кончившем нажатие; срок вышел — на последнем письме") {
        val servers = listOf(server("s001", Entry.Ru))
        val dead = listOf(pressed(servers), *confirmed, answer("s001", Verdict.Unreachable, at = 500))
        render(dead).stopwatch shouldBe Stopwatch.Stopped(0, 500)
        render(dead + Letter.LineObserved(2_000, whitelist)).stopwatch shouldBe Stopwatch.Stopped(0, 500)
        render(listOf(pressed(servers), Letter.LineObserved(10, whitelist)), closed = true).stopwatch shouldBe Stopwatch.Stopped(0, 10)
    }

    test("секундомер: до нажатия и на экране открытия — нет") {
        render(emptyList()).stopwatch shouldBe Stopwatch.None
        render(listOf(Letter.Opened(atMs = 0, os = mts, vpn = Vpn.Off))).stopwatch shouldBe Stopwatch.None
    }

    test("до нажатия — Idle") {
        render(emptyList()).button shouldBe Button.Idle
    }

    test("до LineObserved — Searching, режим и улика ещё неизвестны, оператор уже есть") {
        val screen = render(listOf(pressed(listOf(server("s001", Entry.Ru)))))
        screen.button shouldBe Button.Searching
        screen.network shouldBe NetworkCard.Measuring(mts)
    }

    test("срок трубопровода вышел посреди перебора → Red, «не найдено» со словами о сроке; подозрение без «проверяю»") {
        val letters = listOf(pressed(listOf(server("s001", Entry.Ru), server("s002", Entry.Ru))), Letter.LineObserved(10, whitelist))
        val screen = render(letters, Instagram, closed = true)
        screen.button shouldBe Button.Red
        screen.network.shouldBeInstanceOf<NetworkCard.Measured>().evidence shouldBe "заграница не отвечает, РФ работает"
        screen.result.shouldBeInstanceOf<ResultCard.NotFound>().lines.last() shouldBe
            "Инста: за отведённое время рабочий сервер не нашёлся — нажми ещё раз."
        // Кончившееся нажатие срок не меняет.
        render(letters + answer("s001", Verdict.TelegramDc(3)), closed = true).button shouldBe Button.Green
    }

    test("повторное нажатие: выход подтвердился после линии → «текущий выход работает»") {
        val s = server("s001", Entry.Ru)
        val letters = listOf(
            Letter.Pressed(atMs = 0, os = mts, servers = listOf(s), currentExit = s.id, memory = Memory.Empty),
            *confirmed,
            answer("s001", Verdict.TelegramDc(3), at = 500),
        )
        render(letters).result.shouldBeInstanceOf<ResultCard.Found>().coverage shouldBe "текущий выход работает"
    }

    test("найден до подтверждения — Green, подозрение без «проверяю»") {
        val servers = listOf(server("s001", Entry.Ru))
        val screen = render(listOf(pressed(servers), Letter.LineObserved(10, whitelist), answer("s001", Verdict.TelegramDc(3), at = 300)))
        screen.button shouldBe Button.Green
        val card = screen.network.shouldBeInstanceOf<NetworkCard.Measured>()
        card.scenario shouldBe Scenario.SuspectWhitelist
        card.evidence shouldBe "заграница не отвечает, РФ работает"
    }

    test("подозрение посреди перебора — «проверяю ещё раз»") {
        val screen = render(listOf(pressed(listOf(server("s001", Entry.Ru), server("s002", Entry.Ru))), Letter.LineObserved(10, whitelist)))
        screen.network.shouldBeInstanceOf<NetworkCard.Measured>().evidence shouldBe "заграница не отвечает, РФ работает — проверяю ещё раз"
    }

    test("Whitelist + Found → Green, coverage 'проверено 8 из 11 входов РФ'") {
        val servers = (1..11).map { server("s%03d".format(it), Entry.Ru, name = "RU-$it -> NL-7") }
        val answers = (1..7).map { answer("s%03d".format(it), Verdict.Unreachable) } + answer("s008", Verdict.TelegramDc(3), at = 840)
        val screen = render(listOf(pressed(servers), *confirmed) + answers)
        screen.button shouldBe Button.Green
        screen.network.shouldBeInstanceOf<NetworkCard.Measured>().evidence shouldBe "заграница не отвечает, РФ работает"
        screen.result shouldBe ResultCard.Found(
            serverName = "RU-8 -> NL-7", transport = Transport.Grpc, probedAtMs = 840, coverage = "проверено 8 из 11 входов РФ",
        )
    }

    test("пример с разными подписями → Red и строки в этом порядке") {
        val chains = (1..7).map { server("s%03d".format(it), Entry.Ru, name = "RU-$it -> NL") }
        val singles = (8..11).map { server("s%03d".format(it), Entry.Ru, name = "RU-$it") }
        val foreign = (12..92).map { server("s%03d".format(it), Entry.Foreign("NL")) }
        val verdicts = chains.associate { it.id to (Verdict.TlsSilence as Verdict) } +
            singles.take(3).associate { it.id to Verdict.Unreachable } + mapOf(ServerId("s011") to Verdict.TelegramDc(1))
        val screen = render(runPress(pressed(chains + singles + foreign), FakeWorld(whitelist, verdicts)).letters)
        screen.button shouldBe Button.Red
        screen.result.shouldBeInstanceOf<ResultCard.NotFound>().lines shouldBe listOf(
            "Белые списки: заграница не отвечает, РФ работает.",
            "Проверил всё, что в твоём конфиге может пройти белые списки, — 11 входов в РФ:",
            "7 входов в РФ — тишина после рукопожатия: оператор режет эти адреса",
            "3 входа в РФ — не отвечают: их адресов нет в белом списке",
            "1 вход в РФ — соединился, но Телега не ответила",
            "Остальные 81 сервер — зарубежные, при белых списках недостижимы, не трогал.",
            "С этим конфигом белые списки не пробить.",
            "Российские сайты работают напрямую. Нужен сервер с входом в РФ из белого списка — спроси у своего VPN-сервиса.",
        )
    }

    test("белые списки без Ru-входов → вывод про конфиг") {
        val screen = render(listOf(pressed(listOf(server("s001", Entry.Foreign("NL")))), *confirmed))
        screen.result.shouldBeInstanceOf<ResultCard.NotFound>().lines shouldContain "В твоём конфиге нет ни одного входа в РФ."
    }

    test("Offline → Red, 'Нет сети.'") {
        val screen = render(listOf(pressed(emptyList()), Letter.LineObserved(10, line(Reach.Down, Reach.Down))))
        screen.button shouldBe Button.Red
        screen.result.shouldBeInstanceOf<ResultCard.NotFound>().lines shouldContain "Нет сети."
    }

    test("towerLine: известная вышка — EARFCN и RSRP, cellId на экран не попадает") {
        val os = OsNetwork.Cellular("MTS", "25001", Tower.Known(cellId = 123456789, earfcn = 1575, rsrpDbm = -100))
        towerLine(os) shouldBe "вышка 1575 \u00b7 \u2212100 дБм"
    }

    test("towerLine: неизвестная вышка, Wi-Fi и None — строки нет") {
        towerLine(OsNetwork.Cellular("MTS", "25001", Tower.Unknown)) shouldBe null
        towerLine(OsNetwork.Wifi) shouldBe null
        towerLine(OsNetwork.None) shouldBe null
    }

    test("render(letters, Instagram): слова — «Инста», не «Телега»") {
        val servers = listOf(server("s001", Entry.Ru), server("s002", Entry.Ru))
        val verdicts = mapOf(ServerId("s001") to (Verdict.TelegramDc(1) as Verdict), ServerId("s002") to Verdict.TelegramDc(1))
        val dead = render(runPress(pressed(servers), FakeWorld(whitelist, verdicts)).letters, Instagram)
        val lines = dead.result.shouldBeInstanceOf<ResultCard.NotFound>().lines
        lines shouldContain "2 входа в РФ — соединился, но Инста не ответила"
        lines.none { "Телега" in it } shouldBe true

        exitNote(ExitState.Checking(ExitRef("NL-7", Transport.Grpc)), Instagram) shouldBe "проверяю Инсту…"
    }
})
