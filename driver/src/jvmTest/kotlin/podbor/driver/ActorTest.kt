package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import podbor.core.Deed
import podbor.core.Letter
import podbor.core.Reach

/** Машина-счётчик: состояние — метки букв в порядке шага; на каждую букву — одно дело ProbeLine. */
private class Counter(val until: Int) : Machine<List<Long>> {
    override val head = Head.Press()
    override fun start(first: Letter) = listOf(first.atMs) to listOf(Deed.ProbeLine)
    override fun step(s: List<Long>, l: Letter) = (s + l.atMs) to listOf(Deed.ProbeLine)
    override fun done(s: List<Long>) = s.size >= until
}

private fun lineAt(atMs: Long) = Letter.LineObserved(atMs, line(Reach.Up, Reach.Up))

class ActorTest : FunSpec({

    test("буквы идут строго по одной в порядке прихода, строка Ленты на каждую") {
        val tape = mutableListOf<String>()
        val executed = mutableListOf<Deed>()
        val run = runBlocking {
            act(lineAt(0), Counter(until = 4), timeoutMs = 5_000, execute = { deed, inbox ->
                executed += deed
                // Первое дело шлёт три ответа подряд; остальные молчат.
                if (executed.size == 1) launch { (1L..3L).forEach { inbox.send(lineAt(it)) } }
            }, tape = { tape += it })
        }
        run.state shouldBe listOf(0L, 1L, 2L, 3L)
        run.letters.map { it.atMs } shouldBe listOf(0L, 1L, 2L, 3L)
        run.closed shouldBe false
        // Шапка прогона и строка на каждую букву; срок не вышел — строки Closed нет.
        tape.map(::rowOf).map { it::class } shouldBe listOf(Row.Run::class) + List(4) { Row.Step::class }
    }

    test("срок вышел раньше done → последняя строка Ленты — Closed") {
        val tape = mutableListOf<String>()
        val run = runBlocking { act(lineAt(0), Counter(until = 2), timeoutMs = 100, execute = { _, _ -> }, tape = { tape += it }) }
        run.closed shouldBe true
        val rows = tape.map(::rowOf)
        rows.last() shouldBe Row.Closed(rows.first().shouldBeInstanceOf<Row.Run>().id)
    }

    test("observe — после каждой буквы: состояние и все буквы к этому шагу") {
        val seen = mutableListOf<Pair<List<Long>, Int>>()
        runBlocking {
            act(lineAt(0), Counter(until = 3), timeoutMs = 5_000, execute = { _, inbox ->
                launch { inbox.send(lineAt(seen.size.toLong() + 1)) }
            }, tape = {}, observe = { s, letters -> seen += s to letters.size })
        }
        seen.map { it.second } shouldBe listOf(1, 2, 3)
        seen.last().first.size shouldBe 3
    }

    test("done → петля стоит: поздние ответы не шагаются, их дела не исполняются") {
        val executed = mutableListOf<Deed>()
        val run = runBlocking {
            withTimeout(2_000) {
                act(lineAt(0), Counter(until = 2), timeoutMs = 5_000, execute = { deed, inbox ->
                    executed += deed
                    // Каждое дело отвечает дважды: второй ответ приходит, когда машина уже готова.
                    launch { inbox.send(lineAt(executed.size * 10L)); inbox.send(lineAt(executed.size * 10L + 1)) }
                }, tape = {})
            }
        }
        run.state shouldBe listOf(0L, 10L)
        executed.size shouldBe 2 // дело старта и дело шага, который довёл до done; дальше — ни одного
        run.closed shouldBe false
    }

    test("срок истёк → closed = true, незавершённая проба отменена") {
        val seen = CompletableDeferred<Throwable?>()
        val started = System.currentTimeMillis()
        val run = runBlocking {
            act(lineAt(0), Counter(until = 2), timeoutMs = 200, execute = { _, _ ->
                launch { awaitCancellation() }.invokeOnCompletion { seen.complete(it) }
            }, tape = {})
        }
        (System.currentTimeMillis() - started < 1_500) shouldBe true
        run.closed shouldBe true
        run.state shouldBe listOf(0L)
        runBlocking { seen.await() }.shouldBeInstanceOf<CancellationException>()
    }

    test("медленная проба не держит готовую машину") {
        val started = System.currentTimeMillis()
        runBlocking {
            act(lineAt(0), Counter(until = 2), timeoutMs = 5_000, execute = { _, inbox ->
                launch { inbox.send(lineAt(1)) }
                launch { delay(2_000) }
            }, tape = {})
        }
        (System.currentTimeMillis() - started < 1_500) shouldBe true
    }
})
