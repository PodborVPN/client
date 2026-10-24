package podbor.driver

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import podbor.core.ConnSample
import podbor.core.Conns
import podbor.core.Dest

/** Окно журнала соединений: столько же, сколько у пульса в Ленте, — окна склеиваются в крупные, а не наоборот. */
const val CONN_WINDOW_MS: Long = 10_000

/** Строка access-лога Xray: соединение принято (куда, с какого входа, на какой выход) или что-то другое. */
sealed interface AccessLine {
    data class Accepted(val dest: Dest, val inbound: String, val outbound: String) : AccessLine
    data object Other : AccessLine
}

/** `… from <откуда> accepted <proto>:<host>:<port> [<вход> -> <выход>]`; через балансер Xray пишет и `>>`. */
private val ACCEPTED = Regex("""\saccepted\s+(tcp|udp):(\[[^\]]+]|[^\s:]+):(\d+)\s+\[(\S+)\s+(?:->|>>)\s+(\S+)]""")

fun accessLineOf(line: String): AccessLine = when (val m = ACCEPTED.find(line)) {
    null -> AccessLine.Other
    else -> {
        val (proto, host, port, inbound, outbound) = m.destructured
        AccessLine.Accepted(Dest(proto, host.removePrefix("[").removeSuffix("]"), port.toInt()), inbound, outbound)
    }
}

/**
 * Окно соединений: только выходы пар сервисов ([PAIR_PREFIX]) — остальной трафик человека в Ленту не идёт; счёт по паре
 * и назначению, по порядку пары и назначения — одно и то же окно пишется одинаково.
 */
fun connsOf(lines: List<AccessLine>): List<Conns> =
    lines.mapNotNull { l ->
        when (l) {
            is AccessLine.Accepted -> l.takeIf { it.outbound.startsWith(PAIR_PREFIX) }
            AccessLine.Other -> null
        }
    }
        .groupingBy { it.outbound to it.dest }.eachCount()
        .map { (k, n) -> Conns(k.first, k.second, n) }
        .sortedWith(compareBy<Conns>({ it.pair }, { it.dest.host }, { it.dest.port }, { it.dest.proto }))

/**
 * Журнал соединений одного запуска Xray ([epoch]) до отмены: строки [lines] копятся в окно, раз в [CONN_WINDOW_MS] окно
 * уходит в Ленту и наружу ([emit]); пустое окно не пишется. Лента не пишется (диск полон) — журнал живёт дальше.
 */
suspend fun connLoop(
    lines: ReceiveChannel<String>,
    clock: Clock,
    mono: () -> Long,
    epoch: Long,
    tape: TapeSink,
    emit: (ConnSample) -> Unit,
) = coroutineScope {
    val lock = Mutex()
    val window = mutableListOf<AccessLine>()
    launch {
        for (line in lines) {
            val l = accessLineOf(line)
            lock.withLock { window += l }
        }
    }
    while (true) {
        delay(CONN_WINDOW_MS)
        val conns = connsOf(lock.withLock { window.toList().also { window.clear() } })
        if (conns.isNotEmpty()) {
            val sample = ConnSample(epoch, clock.nowMs(), mono(), CONN_WINDOW_MS, conns)
            arrow.core.Either.catch { tape.write(rowLine(Row.Conns(sample))) }
            emit(sample)
        }
    }
}
