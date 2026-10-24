@file:OptIn(ExperimentalCoroutinesApi::class)

package podbor.driver

import arrow.core.Either
import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.Endpoint
import podbor.core.GENERAL_FOLLOWS
import podbor.core.HARDCODED_SERVICES
import podbor.core.Instagram
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.ProbeSpec
import podbor.core.Reach
import podbor.core.ServerId
import podbor.core.Telegram
import podbor.core.Verdict
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val mts = OsNetwork.Cellular("MTS", "25001")

/** Три зарубежных сервера, сеть чистая (MobileOpen): перебор идёт по всем. */
private val ips = listOf("10.0.0.1", "10.0.0.2", "10.0.0.3")

/**
 * Пробы трубопроводов: Телега годна на [tg], Инста — на [insta] (иначе «сервер жив, сервис мёртв»).
 * [delayMs] — задержка ответа по виду пробы; [silent] — вид, который не отвечает никогда.
 */
private fun prober(
    tg: Set<ServerId>,
    insta: Set<ServerId> = emptySet(),
    delayMs: (ProbeSpec, ServerId) -> Long = { _, _ -> 0 },
    silent: ProbeSpec? = null,
    onProbe: suspend (ProbeSpec, Endpoint) -> Unit = { _, _ -> },
) = Prober { spec, server, _ ->
    onProbe(spec, server.endpoint)
    if (spec == silent) awaitCancellation()
    delay(delayMs(spec, server.id))
    when (spec) {
        ProbeSpec.Telegram -> classify(if (server.id in tg) 3 else 0, Reach.Up)
        ProbeSpec.Instagram -> Verdict.Instagram(cdnOk = server.id in insta, wwwOk = server.id in insta)
    }
}

/** Мир двух трубопроводов: часы стоят — экран не зависит от момента ответа, только от букв. */
private fun pipes(
    prober: Prober,
    gate: EndpointGate = EndpointGate(),
    lineDelayMs: Long = 0,
    saveDelayMs: Long = 0,
    pressTimeoutMs: Long = 60_000,
) = World(
    ips = ips, ruIps = emptySet(), line = line(Reach.Up, Reach.Up), fitsPorts = emptySet(),
    prober = prober, gate = gate, clock = Clock { 1_000L }, lineDelayMs = lineDelayMs, saveDelayMs = saveDelayMs,
    pressTimeoutMs = pressTimeoutMs, services = HARDCODED_SERVICES,
)

private fun World.pressAll(onBoard: (Board) -> Unit = {}): Board {
    lateinit var out: Board
    runTest {
        out = resourceScope {
            val d = driver()
            val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
            d.press(warm, services, mts, onBoard = onBoard)
        }
    }
    return out
}

/** Лента нажатия двух трубопроводов (v2): Телега находит s001, Инсте не годен ни один. */
internal fun pipesTape(): List<String> {
    val w = pipes(prober(tg = setOf(ServerId("s001"))))
    w.pressAll()
    return w.tape.toList()
}

/**
 * Диспетчер-противник на виртуальном времени: из задач, готовых к запуску, первой идёт поданная последней. Так
 * трубопровод, запущенный вторым, первым доходит до своих проб — как бывает на настоящих потоках.
 */
@OptIn(InternalCoroutinesApi::class)
private class LastFirst(private val base: TestDispatcher) : CoroutineDispatcher(), Delay by base {
    private val pending = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(pending) { pending.addLast(block) }
        base.dispatch(context, Runnable { synchronized(pending) { pending.removeLast() }.run() })
    }
}

/**
 * Виртуальное время, когда карточка Телеги впервые стала «найдено»: повторное нажатие, общий выход — s001, проба
 * любого вида 100 мс. Потолок на адрес — 1: Инста (не закреплена) пробует тот же s001. [lastFirst] — трубопроводы
 * стартуют в обратном порядке ([LastFirst]).
 */
private fun tgFoundAt(services: Set<podbor.core.Service>, lastFirst: Boolean = false): Long {
    var foundAt = -1L
    runTest {
        val w = pipes(prober(tg = setOf(ServerId("s001")), insta = setOf(ServerId("s001")), delayMs = { _, _ -> 100 }))
        withContext(if (lastFirst) LastFirst(StandardTestDispatcher(testScheduler)) else EmptyCoroutineContext) {
            resourceScope {
                val d = w.driver()
                val warm = with(d) { warmUp("подписка") }.shouldBeInstanceOf<Either.Right<Warm>>().value
                d.exits.make(GENERAL_FOLLOWS, ServerId("s001"), podbor.core.ChosenOn.Unknown)
                d.press(warm, services, mts) { b ->
                    if (foundAt < 0 && b.cards.any { it.service == Telegram.id && it.status is CardStatus.Found }) foundAt = currentTime
                }
            }
        }
    }
    return foundAt
}

class PipesTest : FunSpec({

    test("Инста на том же общем выходе не задерживает «найдено» Телеги: время как у одной Телеги") {
        val solo = tgFoundAt(setOf(Telegram))
        solo shouldBe 100L
        tgFoundAt(HARDCODED_SERVICES) shouldBe solo
    }

    test("Инста стартует первой (обратный порядок задач) — Телега всё равно первой встаёт на общий адрес") {
        // Ждала бы пробу Инсты — «найдено» не раньше 200 мс.
        tgFoundAt(HARDCODED_SERVICES, lastFirst = true) shouldBe 100L
    }

    test("Телега годна на s001, Инста мертва везде → TG найдено, IG не найдено, кнопка зелёная") {
        val w = pipes(prober(tg = setOf(ServerId("s001"))))
        val board = w.pressAll()
        board.button shouldBe Button.Green
        board.cards.map { it.service } shouldBe listOf(Instagram.id, Telegram.id)
        board.card(Telegram.id).shouldBeInstanceOf<CardStatus.Found>().ref.name shouldBe "Сервер 0"
        board.card(Instagram.id).shouldBeInstanceOf<CardStatus.NotFound>()
    }

    test("доска нажатия не мигает «—»: с первого экрана карточки всех сервисов — «ищу», не Unknown") {
        val boards = mutableListOf<Board>()
        pipes(prober(tg = setOf(ServerId("s001")), delayMs = { spec, _ -> if (spec == ProbeSpec.Telegram) 30 else 0 }))
            .pressAll { synchronized(boards) { boards += it } }
        boards.first().cards.map { it.status } shouldBe listOf(CardStatus.Searching, CardStatus.Searching)
        boards.flatMap { it.cards }.none { it.status == CardStatus.Unknown } shouldBe true
    }

    test("итог не зависит от порядка ответов трубопроводов: две перестановки задержек дают тот же экран") {
        // Внутри трубопровода порядок ответов свой и тот же (s001 раньше s002 раньше s003), меняется только чередование
        // трубопроводов. Общий потолок на адрес связывает их по времени: при perEndpoint = 1 «проверено N из M» Телеги
        // зависит от того, сколько её проб успело встать за Инстой (бывало 2 из 3 против 3 из 3) — здесь
        // он раздвинут, сам потолок и приоритет общего выхода — отдельные тесты.
        fun once(tgMs: Long, igMs: Long) = pipes(
            prober(tg = setOf(ServerId("s001")), delayMs = { spec, id ->
                (if (spec == ProbeSpec.Telegram) tgMs else igMs) + 20 * id.tag.takeLast(1).toLong()
            }),
            gate = EndpointGate(perEndpoint = 2),
        ).pressAll()
        val fastTg = once(tgMs = 5, igMs = 40)
        val fastIg = once(tgMs = 40, igMs = 5)
        fastTg shouldBe fastIg
    }

    test("два трубопровода одновременно просят линию → один замер, сеть у обоих") {
        val w = pipes(prober(tg = setOf(ServerId("s001"))), lineDelayMs = 50)
        val board = w.pressAll()
        w.lineCalls shouldBe 1
        board.network.shouldBeInstanceOf<NetworkCard.Measured>()
    }

    test("два Remember одновременно → в хранилище успехи обоих сервисов") {
        val w = pipes(prober(tg = setOf(ServerId("s001")), insta = setOf(ServerId("s002"))), saveDelayMs = 30)
        val board = w.pressAll()
        board.card(Instagram.id).shouldBeInstanceOf<CardStatus.Found>().ref.name shouldBe "Сервер 1"
        w.saved!!.successes.keys.map { it.service } shouldContainExactlyInAnyOrder listOf(Telegram.id, Instagram.id)
        w.overrides shouldContain "svc.insta=s002"
        w.exits shouldBe listOf("s001")
    }

    test("срок Инсты истёк (проба молчит) → TG найдено, IG не найдено, нажатие возвращается") {
        // Потолок на адрес — отдельный тест: здесь молчащая Инста не должна держать слот Телеги.
        val w = pipes(prober(tg = setOf(ServerId("s001")), silent = ProbeSpec.Instagram), gate = EndpointGate(perEndpoint = 2), pressTimeoutMs = 500)
        val board = w.pressAll()
        board.card(Telegram.id).shouldBeInstanceOf<CardStatus.Found>()
        board.card(Instagram.id).shouldBeInstanceOf<CardStatus.NotFound>()
        board.button shouldBe Button.Green
        w.tape.map(::rowOf).filterIsInstance<Row.Closed>().size shouldBe 1
    }

    test("пробы одного IP:порт из двух трубопроводов не пересекаются во времени") {
        val lock = Any()
        val active = mutableMapOf<Endpoint, Int>()
        val kinds = mutableMapOf<Endpoint, MutableSet<ProbeSpec>>()
        var worst = 0
        val w = pipes(prober(tg = emptySet(), delayMs = { _, _ -> 20 }, onProbe = { spec, e ->
            synchronized(lock) {
                val n = (active[e] ?: 0) + 1
                active[e] = n
                worst = maxOf(worst, n)
                kinds.getOrPut(e) { mutableSetOf() } += spec
            }
            delay(20)
            synchronized(lock) { active[e] = active.getValue(e) - 1 }
        }))
        w.pressAll()
        worst shouldBe 1
        // Оба трубопровода стучались в одни и те же адреса — пересечься было где.
        kinds.values.count { it.size == 2 } shouldBeGreaterThan 0
    }
})
