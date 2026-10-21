package podbor.driver

import arrow.core.Either
import arrow.core.nonEmptyListOf
import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.HARDCODED_SERVICES
import podbor.core.Instagram
import podbor.core.Mark3
import podbor.core.Memory
import podbor.core.MemoryKey
import podbor.core.OsNetwork
import podbor.core.Passport
import podbor.core.Prior
import podbor.core.ProbeSpec
import podbor.core.Reach
import podbor.core.ScanBoard
import podbor.core.Scenario
import podbor.core.Seen
import podbor.core.ServerFacts
import podbor.core.ServiceId
import podbor.core.Success
import podbor.core.Telegram
import podbor.core.Transport
import podbor.core.Verdict
import podbor.core.WhiteHint

private val mts = OsNetwork.Cellular("MTS", "25001")

/** Три зарубежных сервера, сеть чистая: анализ идёт по всем. */
private val ips = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")

/** Телега годна на первых двух, Инста — на первом; [hang] — адрес, чьи пробы не отвечают никогда. */
private fun scanProber(hang: String? = null) = Prober { spec, server, _ ->
    val host = server.endpoint.host
    if (host == hang) awaitCancellation()
    when (spec) {
        ProbeSpec.Telegram -> classify(if (host in setOf("10.0.0.1", "10.0.0.2")) 3 else 0, Reach.Up)
        ProbeSpec.Instagram -> Verdict.Instagram(cdnOk = host == "10.0.0.1", wwwOk = host == "10.0.0.1")
    }
}

/** Выход по SOCKS-порту кандидата: 198.51.100.(индекс+1); [hangPort] — эталон через него молчит до отмены. */
private fun exitProbe(hangPort: Int? = null) = ExitProbe { port ->
    if (port == hangPort) awaitCancellation()
    "198.51.100.${port - 20000 + 1}"
}

private val oldFacts = ServerFacts(Transport.RealityVision, Entry.Foreign("NL"), WhiteHint.Unlikely, Exit.Unknown, Seen.Silent)

private fun scanWorld(
    prober: Prober = scanProber(),
    exits: ExitProbe = exitProbe(),
    passport: Passport? = null,
    memory: Memory = Memory.Empty,
    scanTimeoutMs: Long = 150_000,
) = World(
    ips = ips, ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = emptySet(),
    prober = prober, clock = Clock { 1_000L }, services = HARDCODED_SERVICES, countries = mapOf("198.51.100.1" to "NL"),
    exitProbe = exits, passport = passport, memory = memory, scanTimeoutMs = scanTimeoutMs,
)

private fun World.analyzeAll(
    sniWhite: (Warm) -> Set<String> = { emptySet() },
    onBoard: (ScanBoard) -> Unit = {},
): Pair<Warm, ScanBoard> {
    lateinit var out: Pair<Warm, ScanBoard>
    runTest {
        resourceScope {
            val d = driver()
            val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
            out = warm to d.analyze(warm, services, mts, Prior.None, emptyMap(), sniWhite(warm), onBoard)
        }
    }
    return out
}

/** Лента полного анализа на фейках (для проигрыша рядом с Лентами нажатия). */
internal fun scanTape(): List<String> = scanWorld().also { it.analyzeAll() }.tape.toList()

class ScanDriverTest : FunSpec({

    test("доска анализа идёт от «меряем» к итогу: выходы, сводка по сервисам, все серверы разобраны") {
        val w = scanWorld()
        val boards = mutableListOf<ScanBoard>()
        val (warm, last) = w.analyzeAll(onBoard = { synchronized(boards) { boards += it } })
        boards.first().finished shouldBe false
        boards.flatMap { b -> b.cards.flatMap { c -> c.services.map { it.second } } } shouldContain Mark3.Pending
        last.finished shouldBe true
        last.done shouldBe 3
        last.tallies.associate { it.service to it.works } shouldBe mapOf(Telegram.id to 2, Instagram.id to 1)
        val first = last.cards.single { it.key == warm.servers[0].key }
        first.exit shouldBe Exit.At("198.51.100.1", "NL")
        first.services shouldBe listOf(Telegram.label to Mark3.Works, Instagram.label to Mark3.Works)
        last.cards.single { it.key == warm.servers[1].key }.exit shouldBe Exit.At("198.51.100.2", "?")
    }

    test("паспорт сохранён слиянием с прошлым: старый ключ остаётся, новые серверы — с выходом и откликом") {
        val w = scanWorld(passport = Passport(mapOf("old" to oldFacts), analyzedAtMs = 5))
        val (warm, _) = w.analyzeAll()
        val saved = w.savedPassport.shouldNotBeNull()
        saved.analyzedAtMs shouldBe 1_000L
        saved.servers.keys shouldBe setOf("old") + warm.servers.map { it.key }
        saved.servers.getValue("old") shouldBe oldFacts
        saved.servers.getValue(warm.servers[0].key).exit shouldBe Exit.At("198.51.100.1", "NL")
        saved.servers.getValue(warm.servers[0].key).seen shouldBe Seen.Answered(1_000L)
    }

    test("память анализа: успехи всех сервисов прогона — в хранилище, успехи чужого сервиса не тронуты") {
        val other = MemoryKey("MTS", Scenario.MobileOpen, ServiceId("other"))
        val w = scanWorld(memory = Memory(mapOf(other to nonEmptyListOf(Success("x", 1))), emptyMap()))
        w.analyzeAll()
        val saved = w.saved.shouldNotBeNull()
        saved.successes.keys.map { it.service }.toSet() shouldBe setOf(other.service, Telegram.id, Instagram.id)
        saved.successes.getValue(other) shouldBe nonEmptyListOf(Success("x", 1))
    }

    test("Лента анализа — свой прогон Scan, replay сходится без расхождений до Done") {
        val w = scanWorld()
        w.analyzeAll()
        val runs = replay(w.tape.joinToString("\n"))
        runs.map { it.machine } shouldBe listOf("Scan")
        runs.single().service shouldBe null
        runs.single().outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done
    }

    test("отмена посреди анализа → сохранён частичный паспорт (только разобранные), слитый с прошлым") {
        // Третий сервер молчит и на эталон, и на пробы: два первых разбираются, третий висит до отмены.
        val w = scanWorld(prober = scanProber(hang = "10.0.0.3"), exits = exitProbe(hangPort = 20002),
            passport = Passport(mapOf("old" to oldFacts), analyzedAtMs = 5))
        val twoDone = CompletableDeferred<Unit>()
        lateinit var keys: List<String>
        runTest {
            keys = resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                val job = async { d.analyze(warm, w.services, mts, Prior.None, emptyMap()) { if (it.done == 2) twoDone.complete(Unit) } }
                twoDone.await()
                job.cancel()
                job.join()
                warm.servers.map { it.key }
            }
        }
        val saved = w.savedPassport.shouldNotBeNull()
        saved.servers.keys shouldBe setOf("old", keys[0], keys[1])
        saved.analyzedAtMs shouldBe 1_000L
    }

    test("срок анализа вышел → прогон закрыт, частичный паспорт сохранён, Лента сходится как Closed") {
        val w = scanWorld(prober = scanProber(hang = "10.0.0.3"), exits = exitProbe(hangPort = 20002), scanTimeoutMs = 500)
        val (warm, last) = w.analyzeAll()
        last.finished shouldBe false
        w.savedPassport.shouldNotBeNull().servers.keys shouldBe setOf(warm.servers[0].key, warm.servers[1].key)
        replay(w.tape.joinToString("\n")).single().outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Closed
    }

    test("SNI из белого списка — до паспорта и карточки отдельно от подсказки") {
        val w = scanWorld()
        val (warm, last) = w.analyzeAll(sniWhite = { setOf(it.servers[1].key) })
        val key = warm.servers[1].key
        w.savedPassport.shouldNotBeNull().servers.getValue(key).sniWhite shouldBe true
        w.savedPassport.shouldNotBeNull().servers.getValue(warm.servers[0].key).sniWhite shouldBe false
        last.cards.single { it.key == key }.sniWhite shouldBe true
    }
})
