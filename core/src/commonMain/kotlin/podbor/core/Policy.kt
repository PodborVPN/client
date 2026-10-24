package podbor.core

import kotlinx.serialization.Serializable

/** Класс кандидата — только по измеренному входу; имя из конфига логика не читает. */
@Serializable
enum class CandidateClass { RuEntry, Foreign, UnknownEntry }

fun classOf(server: Server): CandidateClass = when (server.entry) {
    is Entry.Ru -> CandidateClass.RuEntry
    is Entry.Foreign -> CandidateClass.Foreign
    Entry.Unknown -> CandidateClass.UnknownEntry
}

@Serializable
sealed interface SkipReason {
    /** Линия видит белые списки: зарубежный вход недостижим. */
    @Serializable data object ForeignInWhitelist : SkipReason
    /** Похоже на белые списки: зарубежные отложены до подтверждения (вход в РФ годен при обеих гипотезах). */
    @Serializable data object ForeignWhileSuspected : SkipReason
    /** IP:порт недавно дал TlsSilence и ещё под блоком. */
    @Serializable data object BlockedRecently : SkipReason
}

data class SearchPlan(val queue: List<Server>, val skipped: Map<CandidateClass, Map<SkipReason, Int>>)

/**
 * Чистая политика: сценарий → допустимые кандидаты в порядке перебора.
 * При белых списках подсказка паспорта `Likely` допускает и зарубежный вход и ставит сервер сразу после памяти сети;
 * в прочих сценариях подсказка не влияет.
 */
fun plan(
    scenario: Scenario, servers: List<Server>, memory: Memory, key: MemoryKey, nowMs: Long,
    hints: Map<String, WhiteHint> = emptyMap(),
): SearchPlan {
    if (scenario == Scenario.Offline) return SearchPlan(emptyList(), emptyMap())
    val whitelist = scenario == Scenario.MobileWhitelist || scenario == Scenario.SuspectWhitelist
    fun likely(s: Server) = whitelist && hints[s.key] == WhiteHint.Likely
    val skips = mutableListOf<Pair<CandidateClass, SkipReason>>()
    val admissible = servers.filter { s ->
        val cls = classOf(s)
        val reason = when {
            likely(s) -> if (memory.isBlocked(s.endpoint, nowMs)) SkipReason.BlockedRecently else null
            scenario == Scenario.MobileWhitelist && cls == CandidateClass.Foreign -> SkipReason.ForeignInWhitelist
            scenario == Scenario.SuspectWhitelist && cls == CandidateClass.Foreign -> SkipReason.ForeignWhileSuspected
            memory.isBlocked(s.endpoint, nowMs) -> SkipReason.BlockedRecently
            else -> null
        }
        if (reason != null) skips += cls to reason
        reason == null
    }
    val remembered = memory.successesOf(key).mapNotNull { m -> admissible.find { it.key == m.serverKey } }
    val rest = admissible - remembered.toSet()
    val tiers = rest.groupBy { (if (likely(it)) 0 else NOT_LIKELY_SHIFT) + tierOf(scenario, it) }.entries.sortedBy { it.key }.map { it.value }.map { interleaveByHost(it.sortedBy { s -> s.id.tag }) }
    return SearchPlan(
        queue = remembered + tiers.flatten(),
        skipped = skips.groupBy({ it.first }, { it.second }).mapValues { (_, rs) -> rs.groupingBy { it }.eachCount() },
    )
}

/** Сдвиг ступеней не-вероятных серверов: все `Likely` идут раньше. */
private const val NOT_LIKELY_SHIFT = 10

/** Номер ступени: меньше — раньше. */
private fun tierOf(scenario: Scenario, s: Server): Int = when (scenario) {
    Scenario.MobileWhitelist, Scenario.SuspectWhitelist -> whitelistTier(classOf(s))
    // Wi-Fi в итерации 1 — как мобильная чистая [в]; Unclear — не гадаем, как Open.
    Scenario.MobileOpen, Scenario.Wifi, Scenario.Unclear, Scenario.Offline -> openTier(s.entry, s.transport)
}

/** При белых списках — входы в РФ первыми, неизвестные следом, зарубежные последними. */
private fun whitelistTier(c: CandidateClass): Int = when (c) {
    CandidateClass.RuEntry -> 0
    CandidateClass.UnknownEntry -> 1
    CandidateClass.Foreign -> 2
}

/** В открытой сети — входы в РФ последними (белосписочная ёмкость редкая), прочие — по транспорту. */
private fun openTier(entry: Entry, transport: Transport): Int = when (entry) {
    is Entry.Ru -> 3
    is Entry.Foreign, Entry.Unknown -> transportTier(transport)
}

private fun transportTier(t: Transport): Int = when (t) {
    Transport.Xhttp, Transport.Grpc -> 0
    Transport.Ws, is Transport.Other -> 1
    Transport.RealityVision -> 2
}

/** Чередование хостов внутри ступени: не упираться в бюджет одного IP. */
private fun interleaveByHost(servers: List<Server>): List<Server> {
    val groups = servers.groupBy { it.endpoint.host }.values.map { it.toMutableList() }
    return buildList {
        while (groups.any { it.isNotEmpty() }) groups.filter { it.isNotEmpty() }.forEach { add(it.removeFirst()) }
    }
}
