package podbor.core

/**
 * Бюджет одновременных проб: всего и на один IP:порт — блок ставится именно на него: наш перебор — часть сети, которую мы мерим;
 * неосторожный залп сам закрывает человеку его серверы.
 */
/**
 * perEndpoint — кандидатов на один IP:порт одновременно: проба кандидата сама открывает 3 рукопожатия (DC1–DC3),
 * так что 1 кандидат = 3 рукопожатия = потолок на адрес. total — кандидатов всего (на разных адресах).
 */
data class Budget(val total: Int = 6, val perEndpoint: Int = 1)

/** Какие серверы из очереди можно запустить сейчас, не нарушая бюджета; порядок очереди сохраняется. */
fun pickStartable(queue: List<Server>, inFlight: List<Server>, budget: Budget = Budget()): List<Server> =
    pickStartable(queue, inFlight, budget) { it.endpoint }

/** То же для задач с адресом (анализ: задачи «сервер × вид»). */
fun <T> pickStartable(queue: List<T>, inFlight: List<T>, budget: Budget = Budget(), endpoint: (T) -> Endpoint): List<T> =
    queue.fold(inFlight to emptyList<T>()) { (busy, picked), s ->
        val fits = busy.size < budget.total && busy.count { endpoint(it) == endpoint(s) } < budget.perEndpoint
        if (fits) (busy + s) to (picked + s) else busy to picked
    }.second
