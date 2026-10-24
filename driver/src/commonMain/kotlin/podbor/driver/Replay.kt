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
    data class Diverged(val step: Int, val line: Int, val recorded: Recorded, val replayed: Recorded) : Replayed
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
    val lines = tape.lines().withIndex().filter { it.value.isNotBlank() }.map { (i, text) ->
        Numbered(i + 1, Either.catch { rowOf(text) }.mapLeft { e -> "не строка Ленты v1–v$TAPE_FORMAT: ${firstLineOf(e.message)}" })
    }
    val firstRun = lines.indexOfFirst { it.value.getOrNull() is Row.Run }.let { if (it < 0) lines.size else it }
    val v0 = lines.take(firstRun)
        .filter { n -> n.value.fold({ true }, { row -> whose(row) != Whose.Nobody }) }.take(1)
        .map { RunReplay(it.line, "v0", Replayed.Unplayable(it.line, "формат v0: буквы без шапки прогона")) }
    val after = lines.drop(firstRun)
    val broken = after.mapNotNull { n -> n.value.swap().map { RunReplay(n.line, "?", Replayed.Unplayable(n.line, it)) }.getOrNull() }
    val rows = after.mapNotNull { n -> n.value.map { Numbered(n.line, it) }.getOrNull() }
    val runs = rows.mapNotNull { n ->
        when (val row = n.value) {
            is Row.Run -> Numbered(n.line, row)
            is Row.Step, is Row.Closed, is Row.Note, is Row.Pulse, is Row.Lost, is Row.Conns -> null
        }
    }
    val ids = runs.map { it.value.id }.toSet()
    val orphans = rows.filter { n -> orphan(whose(n.value), ids) }
        .map { RunReplay(it.line, "?", Replayed.Unplayable(it.line, "строка прогона без его шапки")) }
    val judged = runs.map { run ->
        RunReplay(run.line, nameOf(run.value.head), judge(run, rows.filter { whose(it.value) == Whose.Of(run.value.id) }), serviceOf(run.value.head))
    }
    return (v0 + judged + broken + orphans).sortedBy { it.line }
}

/** Первая строка сообщения исключения (у kotlinx — многострочные); сообщения нет — «null», как в шаблоне строки. */
private fun firstLineOf(message: String?): String = when (message) {
    null -> "null"
    else -> message.lineSequence().first()
}

/** Чья строка: прогона [Of.run] или ничья (шапка, нота, пульс). */
private sealed interface Whose {
    data object Nobody : Whose
    data class Of(val run: Long) : Whose
}

private fun whose(row: Row): Whose = when (row) {
    is Row.Step -> Whose.Of(row.run)
    is Row.Closed -> Whose.Of(row.run)
    is Row.Run, is Row.Note, is Row.Pulse, is Row.Lost, is Row.Conns -> Whose.Nobody
}

/** Строка прогона, чьей шапки в Ленте нет. */
private fun orphan(w: Whose, ids: Set<Long>): Boolean = when (w) {
    Whose.Nobody -> false
    is Whose.Of -> w.run !in ids
}

private fun nameOf(head: Head): String = when (head) {
    is Head.Press -> "Press"
    is Head.Open -> "Open"
    Head.Scan -> "Scan"
}

/** Чей трубопровод; анализ идёт по всему набору — ничей. */
internal fun serviceOf(head: Head): ServiceId? = when (head) {
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
            is Row.Note, is Row.Closed, is Row.Run, is Row.Pulse, is Row.Lost, is Row.Conns -> null
        }
    }
    val closed = rows.any { it.value is Row.Closed }
    ensure(steps.isNotEmpty()) { Replayed.Unplayable(run.line, "прогон без букв") }
    val first = steps.first()
    when (val head = run.value.head) {
        is Head.Press -> play(PressMachine(head.service), steps, closed)
        is Head.Open -> playOpen(head, first, steps, closed)
        Head.Scan -> play(ScanMachine, steps, closed)
    }
}.merge()

/** Открытие проигрывается от своей первой буквы — она обязана быть Opened. */
private fun playOpen(head: Head.Open, first: Numbered<Row.Step>, steps: List<Numbered<Row.Step>>, closed: Boolean): Replayed =
    when (val opened = first.value.letter) {
        is Letter.Opened -> play(OpenMachine(opened, head.known, head.service), steps, closed)
        is Letter.Pressed, is Letter.LineObserved, is Letter.ProbeAnswered, is Letter.Scan, is Letter.ExitSeen ->
            Replayed.Unplayable(first.line, "шапка открытия, а первая буква не Opened")
    }

/**
 * Машина по буквам прогона (их хоть одна): первое несовпадение дел — Diverged, дальше прогон не судится.
 * [go] сверяет дела буквы [i], выданные из состояния [state] после неё.
 */
private fun <S> play(machine: Machine<S>, steps: List<Numbered<Row.Step>>, closed: Boolean): Replayed {
    tailrec fun go(state: S, deeds: List<Deed>, i: Int): Replayed {
        val recorded = steps[i].value.recorded()
        val replayed = recordedOf(deeds)
        if (!recorded.matches(replayed)) return Replayed.Diverged(i, steps[i].line, recorded, replayed)
        if (i + 1 == steps.size) {
            return Replayed.Converged(steps.size, if (closed) Ending.Closed else if (machine.done(state)) Ending.Done else Ending.Torn)
        }
        val (next, nextDeeds) = machine.step(state, steps[i + 1].value.letter)
        return go(next, nextDeeds, i + 1)
    }

    val (state, deeds) = machine.start(steps.first().value.letter)
    return go(state, deeds, 0)
}
