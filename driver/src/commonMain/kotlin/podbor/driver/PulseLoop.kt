package podbor.driver

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import podbor.core.EpochKind
import podbor.core.PROBES_INBOUND
import podbor.core.Pulse
import podbor.core.PulseSample
import podbor.core.PulseView
import podbor.core.ServiceId
import podbor.core.TUN_INBOUND
import podbor.core.Traffic
import podbor.core.TrafficBook
import podbor.core.after
import podbor.core.view

/** Опрос, пока экран смотрит, и без него: темп на экране свежий, а в фоне Xray не будят чаще нужного. */
const val PULSE_FAST_MS: Long = 1_000
const val PULSE_SLOW_MS: Long = 10_000

/** В Ленту — не чаще: отсчёты накопленные, пропуск теряет разрешение, не байты. */
const val PULSE_TAPE_MS: Long = 10_000

/** Входы проб кандидатов: `in.sNNN` (как в [buildConfig]). */
private const val PROBE_PREFIX = "in."

/** Имя счётчика StatsService: `inbound|outbound>>>тег>>>traffic>>>uplink|downlink`. */
private val STAT = Regex("""^(inbound|outbound)>>>(.+)>>>traffic>>>(uplink|downlink)$""")

/** Что за запуск Xray меряем и ключи итогов на момент отсчёта: сеть ОС и подписка (её хеш). */
data class PulseContext(val kind: EpochKind, val network: String, val subscription: String)

/** Отсчёт из ответа QueryStats; чужие имена (пользователи, мусор) — мимо, половины нет — ноль. */
fun sampleOf(raw: Map<String, Long>, epoch: Long, atMs: Long, monoMs: Long): PulseSample {
    val parsed = raw.mapNotNull { (name, value) -> STAT.find(name)?.destructured?.let { (side, tag, dir) -> Triple(side, tag, dir) to value } }
    fun side(which: String): Map<String, Traffic> =
        parsed.filter { it.first.first == which }.groupBy({ it.first.second }, { it.first.third to it.second }).mapValues { (_, dirs) ->
            Traffic(up = dirs.firstOrNull { it.first == "uplink" }?.second ?: 0, down = dirs.firstOrNull { it.first == "downlink" }?.second ?: 0)
        }
    return PulseSample(epoch, atMs, monoMs, inbound = side("inbound"), outbound = side("outbound"))
}

/**
 * Что из отсчёта нужно пульсу: вход сети (`tun`), сумма входов проб ([PROBES_INBOUND]: сумма накопленных — тоже
 * накопленная) и выходы пар. Вход API растёт от самого опроса, выходы серверов дублируют пробы: это не трафик. Пара с
 * нулём остаётся — «не пользуется» тоже знание.
 */
fun PulseSample.essential(): PulseSample {
    val probes = inbound.filterKeys { it.startsWith(PROBE_PREFIX) }.values
    return copy(
        inbound = inbound.filterKeys { it == TUN_INBOUND } +
            (if (probes.isEmpty()) emptyMap() else mapOf(PROBES_INBOUND to probes.fold(Traffic.Zero, Traffic::plus))),
        outbound = outbound.filterKeys { it.startsWith(PAIR_PREFIX) },
    )
}

/** В Ленту — без нулевых пар: их сотни, и молчат они, пока ими не пользуются; ноль восстанавливается из отсутствия. */
fun PulseSample.forTape(): PulseSample = copy(outbound = outbound.filterValues { it != Traffic.Zero })

/**
 * Опрос счётчиков одного запуска Xray (сессии или анализа) до отмены: отсчёт ([essential], с ключами [context]) → общая
 * свёртка процесса [pulse] (эпохи идут в неё подряд) → вид на экран ([emit]) для [effective] (сервис → тег выхода его
 * пары). В Ленту отсчёт уходит не чаще [PULSE_TAPE_MS] и только изменившийся; записан — итоги отдаются на сохранение
 * ([onTaped]); Лента не пишется (диск полон) — опрос живёт дальше. Сбой API — вид на прежней свёртке к текущему моменту:
 * старше двух окон пара сама уходит в ⊥, итоги остаются. [fast] — экран смотрит.
 */
suspend fun pulseLoop(
    stats: XrayStats,
    clock: Clock,
    mono: () -> Long,
    epoch: Long,
    context: () -> PulseContext,
    fast: () -> Boolean,
    effective: () -> Map<ServiceId, String>,
    pulse: MutableStateFlow<Pulse>,
    tape: TapeSink,
    onTaped: (TrafficBook) -> Unit,
    emit: (PulseView) -> Unit,
) {
    var taped: PulseSample? = null
    while (true) {
        stats.query().fold(
            { emit(pulse.value.view(effective(), mono())) },
            { raw ->
                val at = context()
                val sample = sampleOf(raw, epoch, clock.nowMs(), mono()).essential()
                    .copy(kind = at.kind, network = at.network, subscription = at.subscription)
                val now = pulse.updateAndGet { it.after(sample) }
                emit(now.view(effective(), sample.monoMs))
                val s = sample.forTape()
                val last = taped
                val changed = last == null || last.inbound != s.inbound || last.outbound != s.outbound ||
                    last.network != s.network || last.subscription != s.subscription
                if (changed && (last == null || s.monoMs - last.monoMs >= PULSE_TAPE_MS)) {
                    Either.catch { tape.write(rowLine(Row.Pulse(s))) }.onRight {
                        taped = s
                        onTaped(now.book)
                    }
                }
            },
        )
        delay(if (fast()) PULSE_FAST_MS else PULSE_SLOW_MS)
    }
}
