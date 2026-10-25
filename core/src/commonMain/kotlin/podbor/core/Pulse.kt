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

/**
 * Итоги, что живут дольше процесса: «на этой сети» (приложения + пробы) и «на этой подписке» (через VPN); [past] — итоги
 * VPN прочих подписок, к которым можно вернуться: переключение не обнуляет счёт (поточечная сумма по ключу подписки).
 */
@Serializable
data class TrafficBook(val network: Tally? = null, val vpn: Tally? = null, val past: Map<String, Traffic> = emptyMap())

/**
 * Выбрали подписку [subscription]: её счёт — текущий сразу, не дожидаясь первого отсчёта Xray (иначе «через VPN»
 * показывал бы счёт прежней); прежний — в прошлых. То же, что первый отсчёт на ней, только без трафика.
 */
fun TrafficBook.switchedTo(subscription: String): TrafficBook = vpnAdded(subscription, Traffic.Zero)

/** Итоги без подписки [subscription] — она удалена: ни текущего, ни прошлого счёта. */
fun TrafficBook.without(subscription: String): TrafficBook =
    copy(vpn = if (vpn != null && vpn.key == subscription) null else vpn, past = past - subscription)

/** Точка счётчика одного выхода. */
data class PulsePoint(val monoMs: Long, val traffic: Traffic)

/**
 * История выхода внутри эпохи: точки окна (и одна до него — база темпа) и момент последнего роста ↓. Счётчик убыл в
 * той же эпохе — история с нуля: интервал не наблюдён (⊥), а не отрицательный трафик.
 */
data class Lane(val epoch: Long, val points: List<PulsePoint>, val answered: Grew)

/** Когда последний раз рос ↓ выхода: не рос в эпохе или в момент [At.monoMs]. */
sealed interface Grew {
    data object Never : Grew
    data class At(val monoMs: Long) : Grew
}

/** Сколько назад рос ↓ пары: не рос в сессии или [Ago.ms] назад. */
sealed interface Since {
    data object Never : Since
    data class Ago(val ms: Long) : Since
}

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

/**
 * Начало тега входов проб (`in.sNNN`): собственный трафик Podbor — пробы нажатия и анализ. Каждый вход — своей меткой:
 * вход, пропавший из одного отсчёта, не роняет сумму и не даёт второго счёта. Строки Ленты с суммой под `in.*` — тот же
 * вход, читаются так же.
 */
const val PROBE_INBOUND_PREFIX = "in."

/** Ключ итога «на этой сети»: Wi-Fi — один (имя сети не читаем), сотовая — по MCC MNC; нет сети — нет ключа. */
fun networkKey(os: OsNetwork): String? = when (os) {
    OsNetwork.Wifi -> "wifi"
    is OsNetwork.Cellular -> "cell:${os.mccMnc}"
    OsNetwork.None -> null
}

fun Pulse.after(s: PulseSample): Pulse {
    val point = { t: Traffic -> PulsePoint(s.monoMs, t) }
    val lanes = s.outbound.mapValues { (tag, t) -> laneAfter(lanes[tag], s.epoch, point(t)) }
    // Выход, которого в отсчёте нет, ничего не сообщил: история остаётся до своего окна.
    val kept = this.lanes.filterKeys { it !in s.outbound }.filterValues { lane -> s.monoMs - lane.points.last().monoMs <= PULSE_WINDOW_MS }
    val apps = grown(s, TUN_INBOUND)
    val probeKeys = s.inbound.keys.filter { it.startsWith(PROBE_INBOUND_PREFIX) }
    val probes = probeKeys.fold(Traffic.Zero) { sum, k -> sum + grown(s, k) }
    val marks = marks + (listOf(TUN_INBOUND) + probeKeys).filter { it in s.inbound }.map { k -> k to Mark(s.epoch, s.inbound.getValue(k)) }
    return Pulse(kept + lanes, marks, book.copy(network = added(book.network, s.network, apps + probes)).vpnAdded(s.subscription, apps))
}

/**
 * Прирост VPN на подписке [key]: та же (или ключ пуст) — копится в текущий; другая — текущий уходит в [TrafficBook.past],
 * новая продолжает свой прошлый итог.
 */
private fun TrafficBook.vpnAdded(key: String, t: Traffic): TrafficBook = when {
    key.isEmpty() || (vpn != null && vpn.key == key) -> copy(vpn = added(vpn, key, t))
    vpn == null -> copy(vpn = Tally(key, past.getOrElse(key) { Traffic.Zero } + t), past = past - key)
    else -> copy(vpn = Tally(key, past.getOrElse(key) { Traffic.Zero } + t), past = past - key + (vpn.key to vpn.total))
}

/**
 * Прирост входа [key] от прошлого отсчёта: телескоп внутри эпохи; новая эпоха — от неявного нуля; убыл в той же эпохе —
 * счётчик сброшен, прирост — само значение (нижняя граница, а не минус). Входа нет в отсчёте — ноль.
 */
private fun Pulse.grown(s: PulseSample, key: String): Traffic {
    val now = s.inbound[key]
    if (now == null) return Traffic.Zero
    val was = marks[key]
    return when {
        was == null || was.epoch != s.epoch || now.down < was.traffic.down || now.up < was.traffic.up -> now
        else -> Traffic(now.up - was.traffic.up, now.down - was.traffic.down)
    }
}

/**
 * Прибавить к итогу ключа [key]; другой ключ — итог с нуля, и прирост интервала уходит новому (смену ключа видим только
 * на отсчёте). Ключ пуст (сети нет или ещё не известна) — ключа не знаем, а не «другая сеть»: прирост копится в прежний
 * итог, прежнего нет — никуда.
 */
private fun added(tally: Tally?, key: String, t: Traffic): Tally? = when {
    tally == null -> if (key.isEmpty()) null else Tally(key, t)
    key.isEmpty() || tally.key == key -> tally.copy(total = tally.total + t)
    else -> Tally(key, t)
}

/** Отсчёт выхода в его историю; истории нет — с этой точки. */
private fun laneAfter(lane: Lane?, epoch: Long, p: PulsePoint): Lane = when (lane) {
    null -> Lane(epoch, listOf(p), Grew.Never)
    else -> lane.next(epoch, p)
}

private fun Lane.next(epoch: Long, p: PulsePoint): Lane {
    val last = points.last()
    return when {
        this.epoch != epoch || p.traffic.down < last.traffic.down || p.traffic.up < last.traffic.up -> Lane(epoch, listOf(p), Grew.Never)
        else -> {
            val points = (this.points + p).let { all ->
                // База — последняя точка не позже начала окна; всё раньше неё окну не нужно.
                val base = all.indexOfLast { it.monoMs <= p.monoMs - PULSE_WINDOW_MS }.coerceAtLeast(0)
                all.drop(base)
            }
            Lane(epoch, points, if (p.traffic.down > last.traffic.down) Grew.At(p.monoMs) else answered)
        }
    }
}

/** Что известно о паре: ⊥ (не наблюдали) или темп вниз/вверх (Б/с) и сколько назад рос ↓ (null — не рос в сессии). */
sealed interface PairPulse {
    data object Unseen : PairPulse
    data class Seen(val downBps: Long, val upBps: Long, val answered: Since) : PairPulse
}

/** Вид пульса: темп пар, итог «на этой сети» и «через VPN на этой подписке» — с ключами: экран сверит сеть сам. */
data class PulseView(val pairs: Map<ServiceId, PairPulse>, val network: Tally?, val vpn: Tally?) {
    companion object {
        val Empty = PulseView(emptyMap(), null, null)
    }
}

/** Вид пульса для [effective] (сервис → тег выхода его пары) к моменту [nowMonoMs]; отсчёт старше двух окон — ⊥. */
fun Pulse.view(effective: Map<ServiceId, String>, nowMonoMs: Long): PulseView =
    PulseView(pairs = effective.mapValues { (_, tag) -> pulseOf(lanes[tag], nowMonoMs) }, network = book.network, vpn = book.vpn)

private fun pulseOf(lane: Lane?, nowMonoMs: Long): PairPulse = when (lane) {
    null -> PairPulse.Unseen
    else -> lane.seen(nowMonoMs)
}

private fun Lane.seen(nowMonoMs: Long): PairPulse {
    if (points.size < 2) return PairPulse.Unseen
    val base = points.first()
    val last = points.last()
    val ms = last.monoMs - base.monoMs
    return when {
        nowMonoMs - last.monoMs > 2 * PULSE_WINDOW_MS -> PairPulse.Unseen
        ms <= 0 -> PairPulse.Unseen
        else -> PairPulse.Seen(
            downBps = (last.traffic.down - base.traffic.down) * 1_000 / ms,
            upBps = (last.traffic.up - base.traffic.up) * 1_000 / ms,
            answered = sinceOf(answered, nowMonoMs),
        )
    }
}

private fun sinceOf(g: Grew, nowMonoMs: Long): Since = when (g) {
    Grew.Never -> Since.Never
    is Grew.At -> Since.Ago((nowMonoMs - g.monoMs).coerceAtLeast(0))
}
