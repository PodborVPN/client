package podbor.driver

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull
import podbor.core.EpochKind
import podbor.core.PROBE_INBOUND_PREFIX
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

/** Итоги — на диск не чаще (и на конце запуска Xray — край): убитый процесс теряет не больше минуты итогов. */
const val PULSE_SAVE_MS: Long = 60_000

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
 * Что из отсчёта нужно пульсу: вход сети (`tun`), входы проб (каждый — своя метка в ядре) и выходы пар. Вход API растёт
 * от самого опроса, выходы серверов дублируют пробы: это не трафик. Пара с нулём остаётся — «не пользуется» тоже знание.
 */
fun PulseSample.essential(): PulseSample = copy(
    inbound = inbound.filterKeys { it == TUN_INBOUND || it.startsWith(PROBE_INBOUND_PREFIX) },
    outbound = outbound.filterKeys { it.startsWith(PAIR_PREFIX) },
)

/**
 * В Ленту — без нулевых входов проб и пар: их сотни, и молчат они, пока ими не пользуются; ноль восстанавливается из
 * отсутствия.
 */
fun PulseSample.forTape(): PulseSample =
    copy(inbound = inbound.filterValues { it != Traffic.Zero }, outbound = outbound.filterValues { it != Traffic.Zero })

/**
 * Опрос счётчиков одного запуска Xray (сессии или анализа) до отмены: отсчёт ([essential], с ключами [context]) → общая
 * свёртка процесса [pulse] (эпохи идут в неё подряд) → вид на экран ([emit]) для [effective] (сервис → тег выхода его
 * пары). В Ленту отсчёт уходит не чаще [PULSE_TAPE_MS] и только изменившийся; Лента не пишется (диск полон) — опрос
 * живёт дальше. Итоги — на сохранение ([save]) не чаще [PULSE_SAVE_MS] и только изменившиеся. Сбой API — вид на прежней
 * свёртке к текущему моменту: старше двух окон пара сама уходит в ⊥, итоги остаются. [watching] — экран смотрит: опрос
 * частый; не смотрит — редкий, но экран открыли — следующий отсчёт сразу, без досыпания редкого шага.
 */
suspend fun pulseLoop(
    stats: XrayStats,
    clock: Clock,
    mono: () -> Long,
    epoch: Long,
    context: () -> PulseContext,
    watching: StateFlow<Boolean>,
    effective: () -> Map<ServiceId, String>,
    pulse: MutableStateFlow<Pulse>,
    tape: TapeSink,
    save: (TrafficBook) -> Unit,
    emit: (PulseView) -> Unit,
) {
    var taped: PulseSample? = null
    var saved: Pair<Long, TrafficBook>? = null
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
                    Either.catch { tape.write(rowLine(Row.Pulse(s))) }.onRight { taped = s }
                }
                val was = saved
                if (was == null || (was.second != now.book && s.monoMs - was.first >= PULSE_SAVE_MS)) {
                    save(now.book)
                    saved = s.monoMs to now.book
                }
            },
        )
        if (watching.value) delay(PULSE_FAST_MS) else withTimeoutOrNull(PULSE_SLOW_MS) { watching.first { it } }
    }
}
