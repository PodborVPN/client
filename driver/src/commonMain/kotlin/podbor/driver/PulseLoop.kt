package podbor.driver

import kotlinx.coroutines.delay
import podbor.core.Pulse
import podbor.core.PulseSample
import podbor.core.PulseView
import podbor.core.ServiceId
import podbor.core.Traffic
import podbor.core.after
import podbor.core.view

/** Опрос, пока экран смотрит, и без него: темп на экране свежий, а в фоне Xray не будят чаще нужного. */
const val PULSE_FAST_MS: Long = 1_000
const val PULSE_SLOW_MS: Long = 10_000

/** В Ленту — не чаще: отсчёты накопленные, пропуск теряет разрешение, не байты. */
const val PULSE_TAPE_MS: Long = 10_000

/** Имя счётчика StatsService: `inbound|outbound>>>тег>>>traffic>>>uplink|downlink`. */
private val STAT = Regex("""^(inbound|outbound)>>>(.+)>>>traffic>>>(uplink|downlink)$""")

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
 * Опрос счётчиков Xray сессии до отмены: отсчёт → свёртка [Pulse] → вид на экран ([emit]) для [effective] (сервис →
 * тег выхода его пары). В Ленту отсчёт уходит не чаще [PULSE_TAPE_MS] и только изменившийся. Сбой API — вида нет,
 * прежний остаётся на экране, опрос идёт дальше. [fast] — экран смотрит.
 */
suspend fun pulseLoop(
    stats: XrayStats,
    clock: Clock,
    mono: () -> Long,
    epoch: Long,
    fast: () -> Boolean,
    effective: () -> Map<ServiceId, String>,
    tape: TapeSink,
    emit: (PulseView) -> Unit,
) {
    var pulse = Pulse.Empty
    var taped: PulseSample? = null
    while (true) {
        stats.query().onRight { raw ->
            val s = sampleOf(raw, epoch, clock.nowMs(), mono())
            pulse = pulse.after(s)
            emit(pulse.view(effective(), s.monoMs))
            val last = taped
            val changed = last == null || last.inbound != s.inbound || last.outbound != s.outbound
            if (changed && (last == null || s.monoMs - last.monoMs >= PULSE_TAPE_MS)) {
                tape.write(rowLine(Row.Pulse(s)))
                taped = s
            }
        }
        delay(if (fast()) PULSE_FAST_MS else PULSE_SLOW_MS)
    }
}
