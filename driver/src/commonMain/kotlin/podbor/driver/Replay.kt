package podbor.driver

import arrow.core.Either
import arrow.core.merge
import arrow.core.raise.either
import arrow.core.raise.ensure
import podbor.core.Deed
import podbor.core.Letter
import podbor.core.ServiceId

/*
 * Прогон Ленты: машина ядра, восстановленная из шапки, получает буквы
 * прогона и на каждом шаге даёт дела — они сверяются с записанными. Сети нет: ответы мира — буквы Ленты.
 * Край (пробы, Xray, ОС) не воспроизводится; его решения мимо машины — ноты, прогон их пропускает.
 */

/** Чем прогон кончился в бою. Torn — ни готовности, ни срока: прогон оборван (нажатие вытеснило открытие). */
enum class Ending { Done, Closed, Torn }

sealed interface Replayed {
    data class Converged(val steps: Int, val ending: Ending) : Replayed
    /** step — номер буквы в прогоне с нуля; line — строка файла с единицы. */
    data class Diverged(val step: Int, val line: Int, val recorded: List<Deed>, val replayed: List<Deed>) : Replayed
    data class Unplayable(val line: Int, val why: String) : Replayed
}

/** line — строка шапки (у v0 — первая строка файла); machine — Press / Open / Scan / v0; service — чей трубопровод (нет шапки или анализ — null). */
data class RunReplay(val line: Int, val machine: String, val outcome: Replayed, val service: ServiceId? = null)

/** Строка файла с её номером (с единицы). */
private data class Numbered<T>(val line: Int, val value: T)

/**
 * Все прогоны Ленты по строке шапки. Строки прогона собираются по его id, а не по соседству: прогоны пишут в одну
 * Ленту одновременно. Буквы до первой шапки — Лента v0, машиной не проигрывается. Непрочитанная строка (мусор,
 * оборванная последняя — «Поделиться» читал живую ленту) — своя запись «?»: прогоны вокруг судятся по целым строкам.
 */
fun replay(tape: String): List<RunReplay> {
    val lines = tape.lines().mapIndexedNotNull { i, text ->
        text.takeIf { it.isNotBlank() }?.let { Numbered(i + 1, Either.catch { rowOf(it) }.mapLeft { e -> "не строка Ленты v1–v$TAPE_FORMAT: ${e.message?.lineSequence()?.first()}" }) }
    }
    val firstRun = lines.indexOfFirst { it.value.getOrNull() is Row.Run }.let { if (it < 0) lines.size else it }
    val v0 = lines.take(firstRun)
        .firstOrNull { n -> n.value.fold({ true }, { row -> runOf(row) != null }) }
        ?.let { RunReplay(it.line, "v0", Replayed.Unplayable(it.line, "формат v0: буквы без шапки прогона")) }
    val after = lines.drop(firstRun)
    val broken = after.mapNotNull { n -> n.value.leftOrNull()?.let { RunReplay(n.line, "?", Replayed.Unplayable(n.line, it)) } }
    val rows = after.mapNotNull { n -> n.value.getOrNull()?.let { Numbered(n.line, it) } }
    val runs = rows.mapNotNull { n ->
        when (val row = n.value) {
            is Row.Run -> Numbered(n.line, row)
            is Row.Step, is Row.Closed, is Row.Note, is Row.Pulse -> null
        }
    }
    val ids = runs.map { it.value.id }.toSet()
    val orphans = rows.filter { n -> runOf(n.value)?.let { it !in ids } ?: false }
        .map { RunReplay(it.line, "?", Replayed.Unplayable(it.line, "строка прогона без его шапки")) }
    val judged = runs.map { run ->
        RunReplay(run.line, nameOf(run.value.head), judge(run, rows.filter { runOf(it.value) == run.value.id }), serviceOf(run.value.head))
    }
    return (listOfNotNull(v0) + judged + broken + orphans).sortedBy { it.line }
}

/** Чей прогон строка; шапка, нота и пульс — ничьи. */
private fun runOf(row: Row): Long? = when (row) {
    is Row.Step -> row.run
    is Row.Closed -> row.run
    is Row.Run, is Row.Note, is Row.Pulse -> null
}

private fun nameOf(head: Head): String = when (head) {
    is Head.Press -> "Press"
    is Head.Open -> "Open"
    Head.Scan -> "Scan"
}

/** Чей трубопровод; анализ идёт по всему набору — ничей. */
private fun serviceOf(head: Head): ServiceId? = when (head) {
    is Head.Press -> head.service
    is Head.Open -> head.service
    Head.Scan -> null
}

/** Судить один прогон: шапка и его строки по порядку. */
private fun judge(run: Numbered<Row.Run>, rows: List<Numbered<Row>>): Replayed = either {
    ensure(run.value.v in 1..TAPE_FORMAT) { Replayed.Unplayable(run.line, "формат v${run.value.v}, прогон знает v1–v$TAPE_FORMAT") }
    val steps = rows.mapNotNull { n ->
        when (val row = n.value) {
            is Row.Step -> Numbered(n.line, row)
            is Row.Note, is Row.Closed, is Row.Run, is Row.Pulse -> null
        }
    }
    val closed = rows.any { it.value is Row.Closed }
    val first = steps.firstOrNull() ?: raise(Replayed.Unplayable(run.line, "прогон без букв"))
    when (val head = run.value.head) {
        is Head.Press -> play(PressMachine(head.service), steps, closed)
        is Head.Open -> when (val opened = first.value.letter) {
            is Letter.Opened -> play(OpenMachine(opened, head.known, head.service), steps, closed)
            is Letter.Pressed, is Letter.LineObserved, is Letter.ProbeAnswered, is Letter.Scan, is Letter.ExitSeen ->
                raise(Replayed.Unplayable(first.line, "шапка открытия, а первая буква не Opened"))
        }
        Head.Scan -> play(ScanMachine, steps, closed)
    }
}.merge()

/** Машина по буквам прогона: первое несовпадение дел — Diverged, дальше прогон не судится. */
private fun <S> play(machine: Machine<S>, steps: List<Numbered<Row.Step>>, closed: Boolean): Replayed {
    fun diverged(i: Int, replayed: List<Deed>): Replayed.Diverged? =
        steps[i].takeIf { it.value.deeds != replayed }?.let { Replayed.Diverged(i, it.line, it.value.deeds, replayed) }

    tailrec fun go(state: S, i: Int): Replayed =
        if (i == steps.size) {
            Replayed.Converged(steps.size, if (closed) Ending.Closed else if (machine.done(state)) Ending.Done else Ending.Torn)
        } else {
            val (next, deeds) = machine.step(state, steps[i].value.letter)
            diverged(i, deeds) ?: go(next, i + 1)
        }

    val (state, deeds) = machine.start(steps.first().value.letter)
    return diverged(0, deeds) ?: go(state, 1)
}
