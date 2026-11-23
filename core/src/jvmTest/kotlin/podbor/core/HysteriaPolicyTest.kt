package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HysteriaPolicyTest : FunSpec({
    val udp = Server(ServerId("s002"), "UDP", Endpoint("203.0.113.7", 443), Transport.Hysteria2, null, Entry.Unknown, "udp")
    val tcp = udp.copy(id = ServerId("s001"), transport = Transport.Grpc, key = "tcp")
    val key = memoryKey(OsNetwork.Wifi, Scenario.Wifi, Telegram.id)

    test("белые списки TCP не исключают зарубежный UDP вход") {
        val foreignUdp = udp.copy(entry = Entry.Foreign("DE"))
        val foreignTcp = tcp.copy(entry = Entry.Foreign("DE"))
        listOf(Scenario.MobileWhitelist, Scenario.SuspectWhitelist).forEach { scenario ->
            val result = plan(scenario, listOf(foreignTcp, foreignUdp), Memory.Empty, key, 1)
            result.queue shouldBe listOf(foreignUdp)
            result.skipped.values.flatMap { it.values }.sum() shouldBe 1
        }
    }

    test("неизвестный транспорт не наследует TCP блок") {
        val unknown = udp.copy(transport = Transport.Other("unknown"))
        val memory = Memory.Empty.block(tcp.endpoint, 0)
        plan(Scenario.Wifi, listOf(unknown), memory, key, 1).queue shouldBe listOf(unknown)
    }

    test("блок TCP не исключает UDP на том же IP и порту") {
        val memory = Memory.Empty.block(tcp.endpoint, 0)
        plan(Scenario.Wifi, listOf(tcp, udp), memory, key, 1).queue shouldBe listOf(udp)
    }

    test("тишина TCP во время подбора оставляет UDP кандидата в очереди") {
        val pressed = Letter.Pressed(0, OsNetwork.Wifi, listOf(tcp, udp), null, Memory.Empty)
        val run = runPress(pressed, FakeWorld(line(Reach.Up, Reach.Up), mapOf(tcp.id to Verdict.TlsSilence, udp.id to Verdict.TelegramDc(3))))
        run.deeds.filterIsInstance<Deed.MakeExit>().map { it.server } shouldBe listOf(udp.id)
    }

    test("тишина UDP повторяется ограниченно и не создаёт блок адреса") {
        val pressed = Letter.Pressed(0, OsNetwork.Wifi, listOf(udp), null, Memory.Empty)
        val run = runPress(pressed, FakeWorld(line(Reach.Up, Reach.Up), mapOf(udp.id to Verdict.NoResponse)))
        run.probed shouldBe listOf(udp.id, udp.id)
        run.deeds.filterIsInstance<Deed.Remember>().all { it.memory.blocks.isEmpty() } shouldBe true
        run.deeds.filterIsInstance<Deed.MakeExit>() shouldBe emptyList()
    }

    test("объяснение не называет ожидающий UDP кандидата заблокированным после тишины TCP") {
        val sibling = tcp.copy(id = ServerId("s003"), key = "tcp-other")
        val letters = listOf(
            Letter.Pressed(0, OsNetwork.Wifi, listOf(tcp, sibling, udp), null, Memory.Empty),
            Letter.LineObserved(10, line(Reach.Up, Reach.Up)),
            Letter.ProbeAnswered(100, tcp.id, Verdict.TlsSilence),
        )
        explain(letters).skipped.values.flatMap { it.keys }.contains(SkipReason.BlockedRecently) shouldBe false
    }
})
