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
 * [atMs] — настенное, для людей. Ключи — теги входов и выходов Xray. [kind] — что за запуск, [network] и [subscription] —
 * ключи итогов на момент отсчёта ([networkKey], хеш подписки); строки Ленты до них — сессия без ключей.
 */
@Serializable
data class PulseSample(
    val epoch: Long,
    val atMs: Long,
    val monoMs: Long,
    val inbound: Map<String, Traffic>,
    val outbound: Map<String, Traffic>,
    val kind: EpochKind = EpochKind.Session,
    val network: String = "",
    val subscription: String = "",
)

/** Запуск Xray: сессия VPN (с TUN — трафик приложений и пробы нажатия) или анализ подписки (без TUN — только пробы). */
@Serializable
enum class EpochKind { Session, Scan }

/** Итог трафика по ключу (сеть, подписка): копится, пока ключ тот же; другой ключ — итог с нуля. */
@Serializable
data class Tally(val key: String, val total: Traffic)

/** Итоги, что живут дольше процесса: «на этой сети» (приложения + пробы) и «на этой подписке» (через VPN). */
@Serializable
data class TrafficBook(val network: Tally? = null, val vpn: Tally? = null)

/** Точка счётчика одного выхода. */
data class PulsePoint(val monoMs: Long, val traffic: Traffic)

/**
 * История выхода внутри эпохи: точки окна (и одна до него — база темпа) и момент последнего роста ↓. Счётчик убыл в
 * той же эпохе — история с нуля: интервал не наблюдён (⊥), а не отрицательный трафик.
 */
data class Lane(val epoch: Long, val points: List<PulsePoint>, val answeredAtMs: Long?)

/** Последнее накопленное значение входа в его эпохе — база прироста. */
data class Mark(val epoch: Long, val traffic: Traffic)

/** Свёртка процесса: истории выходов (темп пар), базы приростов входов и итоги ([book]). */
data class Pulse(val lanes: Map<String, Lane>, val marks: Map<String, Mark>, val book: TrafficBook) {
    companion object {
        val Empty = Pulse(emptyMap(), emptyMap(), TrafficBook())

        /** Свёртка нового процесса поверх итогов, сохранённых прошлым. */
        fun resumed(book: TrafficBook) = Pulse(emptyMap(), emptyMap(), book)
    }
}

/** Вход сети телефона в Xray: его счётчик — трафик приложений через VPN. */
const val TUN_INBOUND = "tun"

/** Сумма входов проб `in.sNNN` (край складывает их сам): собственный трафик Podbor — пробы нажатия и анализ. */
const val PROBES_INBOUND = "in.*"

/** Ключ итога «на этой сети»: Wi-Fi — один (имя сети не читаем), сотовая — по MCC MNC; нет сети — нет ключа. */
fun networkKey(os: OsNetwork): String? = when (os) {
    OsNetwork.Wifi -> "wifi"
    is OsNetwork.Cellular -> "cell:${os.mccMnc}"
    OsNetwork.None -> null
}

fun Pulse.after(s: PulseSample): Pulse {
    val point = { t: Traffic -> PulsePoint(s.monoMs, t) }
    val lanes = s.outbound.mapValues { (tag, t) -> lanes[tag].next(s.epoch, point(t)) }
    // Выход, которого в отсчёте нет, ничего не сообщил: история остаётся до своего окна.
    val kept = this.lanes.filterKeys { it !in s.outbound }.filterValues { lane -> s.monoMs - lane.points.last().monoMs <= PULSE_WINDOW_MS }
    val apps = grown(s, TUN_INBOUND)
    val probes = grown(s, PROBES_INBOUND)
    val marks = marks + listOf(TUN_INBOUND, PROBES_INBOUND).mapNotNull { k -> s.inbound[k]?.let { k to Mark(s.epoch, it) } }
    return Pulse(kept + lanes, marks, TrafficBook(network = book.network.add(s.network, apps + probes), vpn = book.vpn.add(s.subscription, apps)))
}

/**
 * Прирост входа [key] от прошлого отсчёта: телескоп внутри эпохи; новая эпоха — от неявного нуля; убыл в той же эпохе —
 * счётчик сброшен, прирост — само значение (нижняя граница, а не минус). Входа нет в отсчёте — ноль.
 */
private fun Pulse.grown(s: PulseSample, key: String): Traffic {
    val now = s.inbound[key] ?: return Traffic.Zero
    val was = marks[key]
    return when {
        was == null || was.epoch != s.epoch || now.down < was.traffic.down || now.up < was.traffic.up -> now
        else -> Traffic(now.up - was.traffic.up, now.down - was.traffic.down)
    }
}

/** Прибавить к итогу ключа [key]; другой ключ — итог с нуля (и без прироста: ключ сменился — это уже знание). */
private fun Tally?.add(key: String, t: Traffic): Tally = if (this == null || this.key != key) Tally(key, t) else copy(total = total + t)

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

/** Что известно о паре: ⊥ (не наблюдали) или темп вниз/вверх (Б/с) и сколько назад рос ↓ (null — не рос в сессии). */
sealed interface PairPulse {
    data object Unseen : PairPulse
    data class Seen(val downBps: Long, val upBps: Long, val answeredAgoMs: Long?) : PairPulse
}

/** Вид пульса: темп пар, итог «на этой сети» и «через VPN на этой подписке» — с ключами: экран сверит сеть сам. */
data class PulseView(val pairs: Map<ServiceId, PairPulse>, val network: Tally?, val vpn: Tally?) {
    companion object {
        val Empty = PulseView(emptyMap(), null, null)
    }
}

/** Вид пульса для [effective] (сервис → тег выхода его пары) к моменту [nowMonoMs]; отсчёт старше двух окон — ⊥. */
fun Pulse.view(effective: Map<ServiceId, String>, nowMonoMs: Long): PulseView =
    PulseView(pairs = effective.mapValues { (_, tag) -> lanes[tag].seen(nowMonoMs) }, network = book.network, vpn = book.vpn)

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
