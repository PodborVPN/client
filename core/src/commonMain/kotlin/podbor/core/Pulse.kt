package podbor.core

import kotlinx.serialization.Serializable

/*
 * Пульс — величины пары «сервис × сервер» из накопленных счётчиков Xray, без вердиктов. Машина Мура: состояние —
 * свёртка отсчётов ([after]), выход — функция состояния ([view]). Счёт — в целых, темп — по монотонному времени.
 */

/** Окно темпа: база — последний отсчёт не позже его начала. */
const val PULSE_WINDOW_MS: Long = 10_000

/** Байты вверх и вниз. */
@Serializable
data class Traffic(val up: Long, val down: Long) {
    operator fun plus(o: Traffic) = Traffic(up + o.up, down + o.down)

    companion object {
        val Zero = Traffic(0, 0)
    }
}

/**
 * Отсчёт счётчиков Xray: НАКОПЛЕННЫЕ значения, не приросты — пропуск опроса теряет разрешение, не байты. [epoch] —
 * номер запуска Xray (растёт): с новой эпохой счётчики начинаются с нуля. [monoMs] — монотонное время для темпа,
 * [atMs] — настенное, для людей. Ключи — теги входов и выходов Xray.
 */
@Serializable
data class PulseSample(
    val epoch: Long,
    val atMs: Long,
    val monoMs: Long,
    val inbound: Map<String, Traffic>,
    val outbound: Map<String, Traffic>,
)

/** Точка счётчика одного выхода. */
data class PulsePoint(val monoMs: Long, val traffic: Traffic)

/**
 * История выхода внутри эпохи: точки окна (и одна до него — база темпа) и момент последнего роста ↓. Счётчик убыл в
 * той же эпохе — история с нуля: интервал не наблюдён (⊥), а не отрицательный трафик.
 */
data class Lane(val epoch: Long, val points: List<PulsePoint>, val answeredAtMs: Long?)

/** Итог сессии по входу `tun`: сумма закрытых эпох и последнее значение текущей — нижняя граница трафика. */
data class Session(val closed: Traffic, val epoch: Long, val last: Traffic)

data class Pulse(val lanes: Map<String, Lane>, val session: Session?) {
    companion object {
        val Empty = Pulse(emptyMap(), null)
    }
}

/** Вход сети телефона в Xray: его счётчик — трафик всей сети через Podbor. */
const val TUN_INBOUND = "tun"

fun Pulse.after(s: PulseSample): Pulse {
    val point = { t: Traffic -> PulsePoint(s.monoMs, t) }
    val lanes = s.outbound.mapValues { (tag, t) -> lanes[tag].next(s.epoch, point(t)) }
    // Выход, которого в отсчёте нет, ничего не сообщил: история остаётся до своего окна.
    val kept = this.lanes.filterKeys { it !in s.outbound }.filterValues { lane -> s.monoMs - lane.points.last().monoMs <= PULSE_WINDOW_MS }
    return Pulse(kept + lanes, s.inbound[TUN_INBOUND]?.let { session.next(s.epoch, it) } ?: session)
}

private fun Lane?.next(epoch: Long, p: PulsePoint): Lane {
    val last = this?.points?.last()
    return when {
        this == null || last == null || this.epoch != epoch || p.traffic.down < last.traffic.down || p.traffic.up < last.traffic.up ->
            Lane(epoch, listOf(p), answeredAtMs = null)
        else -> {
            val points = (this.points + p).let { all ->
                // База — последняя точка не позже начала окна; всё раньше неё окну не нужно.
                val base = all.indexOfLast { it.monoMs <= p.monoMs - PULSE_WINDOW_MS }.coerceAtLeast(0)
                all.drop(base)
            }
            Lane(epoch, points, if (p.traffic.down > last.traffic.down) p.monoMs else answeredAtMs)
        }
    }
}

private fun Session?.next(epoch: Long, t: Traffic): Session = when {
    this == null -> Session(Traffic.Zero, epoch, t)
    this.epoch != epoch || t.down < last.down || t.up < last.up -> Session(closed + last, epoch, t)
    else -> copy(last = t)
}

/** Что известно о паре: ⊥ (не наблюдали) или темп вниз/вверх (Б/с) и сколько назад рос ↓ (null — не рос в сессии). */
sealed interface PairPulse {
    data object Unseen : PairPulse
    data class Seen(val downBps: Long, val upBps: Long, val answeredAgoMs: Long?) : PairPulse
}

data class PulseView(val pairs: Map<ServiceId, PairPulse>, val session: Traffic?) {
    companion object {
        val Empty = PulseView(emptyMap(), null)
    }
}

/** Вид пульса для [effective] (сервис → тег выхода его пары) к моменту [nowMonoMs]; отсчёт старше двух окон — ⊥. */
fun Pulse.view(effective: Map<ServiceId, String>, nowMonoMs: Long): PulseView =
    PulseView(
        pairs = effective.mapValues { (_, tag) -> lanes[tag].seen(nowMonoMs) },
        session = session?.let { it.closed + it.last },
    )

private fun Lane?.seen(nowMonoMs: Long): PairPulse {
    val base = this?.points?.firstOrNull()
    val last = this?.points?.lastOrNull()
    return when {
        this == null || base == null || last == null || points.size < 2 -> PairPulse.Unseen
        nowMonoMs - last.monoMs > 2 * PULSE_WINDOW_MS -> PairPulse.Unseen
        else -> {
            val ms = last.monoMs - base.monoMs
            if (ms <= 0) {
                PairPulse.Unseen
            } else {
                PairPulse.Seen(
                    downBps = (last.traffic.down - base.traffic.down) * 1_000 / ms,
                    upBps = (last.traffic.up - base.traffic.up) * 1_000 / ms,
                    answeredAgoMs = answeredAtMs?.let { (nowMonoMs - it).coerceAtLeast(0) },
                )
            }
        }
    }
}
