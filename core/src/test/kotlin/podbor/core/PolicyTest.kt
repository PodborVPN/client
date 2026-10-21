package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class PolicyTest : FunSpec({

    val yandex = Entry.Ru
    val hetzner = Entry.Foreign("NL")
    val mtsWl = MemoryKey(operator = "MTS", scenario = Scenario.MobileWhitelist)
    val mtsOpen = MemoryKey(operator = "MTS", scenario = Scenario.MobileOpen)

    fun ids(p: SearchPlan) = p.queue.map { it.id.tag }

    test("белые списки: только Ru-входы, зарубежные в skipped с ForeignInWhitelist") {
        val servers = listOf(
            server("s001", yandex), server("s002", hetzner), server("s003", yandex),
            server("s004", hetzner), server("s005", hetzner),
        )
        val p = plan(Scenario.MobileWhitelist, servers, Memory.Empty, mtsWl, nowMs = 0)
        p.queue.size shouldBe 2
        p.skipped[CandidateClass.Foreign]!![SkipReason.ForeignInWhitelist] shouldBe 3
    }

    test("белые списки: память → RuEntry → UnknownEntry; имя со стрелкой порядок не меняет") {
        val servers = listOf(
            server("s001", Entry.Unknown),
            server("s002", yandex, name = "RU-4"),
            server("s003", yandex, name = "RU-2 -> NL-7"),
            server("s005", yandex, name = "RU-3"),
        )
        val memory = Memory.Empty.rememberSuccess(mtsWl, servers[3], atMs = 1_000)
        ids(plan(Scenario.MobileWhitelist, servers, memory, mtsWl, nowMs = 2_000)) shouldBe
            listOf("s005", "s002", "s003", "s001")
    }

    test("белые списки: Likely с зарубежным входом допущен, Unlikely зарубежный — в skipped") {
        val servers = listOf(server("s001", hetzner), server("s002", hetzner), server("s003", yandex))
        val hints = mapOf(servers[0].key to WhiteHint.Likely, servers[1].key to WhiteHint.Unlikely)
        val p = plan(Scenario.MobileWhitelist, servers, Memory.Empty, mtsWl, nowMs = 0, hints = hints)
        ids(p) shouldBe listOf("s001", "s003")
        p.skipped[CandidateClass.Foreign]!![SkipReason.ForeignInWhitelist] shouldBe 1
    }

    test("белые списки: память → Likely(Ru) → Likely(Foreign) → прочие Ru; подозрение — так же") {
        val servers = listOf(
            server("s001", yandex), server("s002", hetzner), server("s003", yandex),
            server("s004", yandex), server("s005", Entry.Unknown),
        )
        val hints = mapOf(servers[1].key to WhiteHint.Likely, servers[2].key to WhiteHint.Likely)
        val memory = Memory.Empty.rememberSuccess(mtsWl, servers[3], atMs = 1_000)
        ids(plan(Scenario.MobileWhitelist, servers, memory, mtsWl, nowMs = 2_000, hints = hints)) shouldBe
            listOf("s004", "s003", "s002", "s001", "s005")
        val sus = mtsWl.copy(scenario = Scenario.SuspectWhitelist)
        ids(plan(Scenario.SuspectWhitelist, servers, Memory.Empty, sus, nowMs = 0, hints = hints)) shouldBe
            listOf("s003", "s002", "s001", "s004", "s005")
    }

    test("MobileOpen: подсказка не влияет на очередь") {
        val servers = listOf(server("s001", hetzner), server("s002", yandex), server("s003", hetzner, transport = Transport.Ws))
        val hints = mapOf(servers[1].key to WhiteHint.Likely, servers[2].key to WhiteHint.Unlikely)
        plan(Scenario.MobileOpen, servers, Memory.Empty, mtsOpen, nowMs = 0, hints = hints) shouldBe
            plan(Scenario.MobileOpen, servers, Memory.Empty, mtsOpen, nowMs = 0)
    }

    test("Likely под блоком по-прежнему исключён") {
        val s = server("s001", hetzner)
        val p = plan(Scenario.MobileWhitelist, listOf(s), Memory.Empty.block(s.endpoint, 0), mtsWl, 1, mapOf(s.key to WhiteHint.Likely))
        p.queue.shouldBeEmpty()
        p.skipped[CandidateClass.Foreign]!![SkipReason.BlockedRecently] shouldBe 1
    }

    test("память другого сценария не поднимает сервер") {
        val servers = listOf(server("s002", yandex), server("s003", yandex))
        val memory = Memory.Empty.rememberSuccess(mtsOpen, servers[1], atMs = 1_000)
        ids(plan(Scenario.MobileWhitelist, servers, memory, mtsWl, nowMs = 2_000)) shouldBe listOf("s002", "s003")
    }

    test("чистая сеть: память → Xhttp/Grpc → Ws/Other → RealityVision → Ru-входы последними") {
        val servers = listOf(
            server("s001", hetzner, transport = Transport.RealityVision),
            server("s002", yandex, transport = Transport.Xhttp),
            server("s003", hetzner, transport = Transport.Ws),
            server("s004", hetzner, transport = Transport.Grpc),
            server("s005", hetzner, transport = Transport.Other("tcp")),
            server("s006", hetzner, transport = Transport.RealityVision),
        )
        val memory = Memory.Empty.rememberSuccess(mtsOpen, servers[5], atMs = 1)
        ids(plan(Scenario.MobileOpen, servers, memory, mtsOpen, nowMs = 2)) shouldBe
            listOf("s006", "s004", "s003", "s005", "s001", "s002")
    }

    test("внутри ступени разные IP чередуются") {
        val servers = listOf(
            server("s001", hetzner, host = "10.0.0.1"), server("s002", hetzner, host = "10.0.0.1"),
            server("s003", hetzner, host = "10.0.0.2"), server("s004", hetzner, host = "10.0.0.2"),
        )
        plan(Scenario.MobileOpen, servers, Memory.Empty, mtsOpen, nowMs = 0).queue.map { it.endpoint.host } shouldBe
            listOf("10.0.0.1", "10.0.0.2", "10.0.0.1", "10.0.0.2")
    }

    test("заблокированный IP:порт исключён до истечения TTL") {
        val s = server("s001", yandex, host = "10.0.0.1", port = 8443)
        val memory = Memory.Empty.block(s.endpoint, atMs = 0)
        plan(Scenario.MobileWhitelist, listOf(s), memory, mtsWl, nowMs = 599_999).let {
            it.queue.shouldBeEmpty()
            it.skipped[CandidateClass.RuEntry]!![SkipReason.BlockedRecently] shouldBe 1
        }
        plan(Scenario.MobileWhitelist, listOf(s), memory, mtsWl, nowMs = 600_000).queue.size shouldBe 1
    }

    test("класс — только по измеренному входу, имя не читается") {
        classOf(server("s001", yandex, name = "🇷🇺 RU-2 → 🇳🇱 NL-7")) shouldBe CandidateClass.RuEntry
        classOf(server("s002", yandex, name = "🇳🇱 Netherlands")) shouldBe CandidateClass.RuEntry
        classOf(server("s003", hetzner, name = "🇷🇺 Russia-29s")) shouldBe CandidateClass.Foreign
        classOf(server("s005", Entry.Unknown)) shouldBe CandidateClass.UnknownEntry
    }

    test("Offline → пустая очередь") {
        plan(Scenario.Offline, listOf(server("s001", yandex)), Memory.Empty, mtsOpen, nowMs = 0).queue.shouldBeEmpty()
    }

    test("вердикт: TelegramDc(2) — Fits, TelegramDc(1) — ServiceDead") {
        Verdict.TelegramDc(2).kind() shouldBe VerdictKind.Fits
        Verdict.TelegramDc(1).kind() shouldBe VerdictKind.ServiceDead
        Verdict.TlsSilence.kind() shouldBe VerdictKind.TlsSilence
        Verdict.Unreachable.kind() shouldBe VerdictKind.Unreachable
    }
})
