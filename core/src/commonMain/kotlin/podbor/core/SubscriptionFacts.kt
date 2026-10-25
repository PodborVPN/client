package podbor.core

/** Когда сервис последний раз заработал через подписку на этой сети: не было; в момент [At.atMs]. */
sealed interface LastWorked {
    data object Never : LastWorked
    data class At(val atMs: Long) : LastWorked
}

data class Worked(val service: Service, val last: LastWorked)

/**
 * Как подписка ведёт себя на этом телефоне — свёртка уже накопленного знания по ключам её серверов: на этой сети
 * ([worked], по сервису); по паспорту — входы в РФ, вероятно для белых списков, ответили из проверенных анализом.
 */
data class SubscriptionFacts(
    val worked: List<Worked>,
    val ruEntries: Int,
    val likelyWhite: Int,
    val answered: Int,
    val checked: Int,
) {
    companion object {
        val Empty = SubscriptionFacts(emptyList(), 0, 0, 0, 0)
    }
}

/**
 * Факты подписки с серверами [keys]: паспорт — только её серверы; успехи — на сети [os] в любом сценарии (белые и
 * открытая сеть — та же сеть для человека), только через её серверы, последний — по каждому из [services].
 */
fun subscriptionFacts(keys: Set<String>, passport: Passport, memory: Memory, os: OsNetwork, services: List<Service>): SubscriptionFacts {
    val mine = passport.servers.filterKeys { it in keys }.values
    val here = memory.successes.filterKeys { it.operator == memoryOperator(os) }
    return SubscriptionFacts(
        worked = services.map { s -> Worked(s, lastWorked(here.filterKeys { it.service == s.id }.values.flatten(), keys)) },
        ruEntries = mine.count { it.entry == Entry.Ru },
        likelyWhite = mine.count { it.whiteHint == WhiteHint.Likely },
        answered = mine.count { it.seen is Seen.Answered },
        checked = mine.size,
    )
}

private fun lastWorked(successes: List<Success>, keys: Set<String>): LastWorked =
    successes.filter { it.serverKey in keys }.fold<Success, LastWorked>(LastWorked.Never) { acc, s -> later(acc, s.atMs) }

private fun later(acc: LastWorked, atMs: Long): LastWorked = when (acc) {
    LastWorked.Never -> LastWorked.At(atMs)
    is LastWorked.At -> if (atMs > acc.atMs) LastWorked.At(atMs) else acc
}

/** Итог «через VPN» подписки [key]: текущий счёт, прошлый или ноль. */
fun TrafficBook.of(key: String): Traffic =
    if (vpn != null && vpn.key == key) vpn.total else past.getOrElse(key) { Traffic.Zero }
