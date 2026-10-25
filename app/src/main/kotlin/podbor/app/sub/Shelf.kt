package podbor.app.sub

import kotlinx.serialization.Serializable

/** Подписки человека и текущая ([currentId]; null — текущей нет: первый запуск или её удалили). */
@Serializable
data class Shelf(val items: List<Subscription> = emptyList(), val currentId: String? = null)

fun Shelf.current(): Subscription? = items.firstOrNull { it.id == currentId }

/**
 * Вставленная подписка [s] — в список и текущая (в момент [nowMs]). Та же (та же ссылка, а без ссылки — тот же текст)
 * — та же запись с прежним id и свежими полями [s], а отметка анализа — прежняя, пока анализ не кончится; иначе новая
 * с id из [newId]. Возвращает список и вставленную запись.
 */
fun Shelf.put(s: Subscription, newId: () -> String, nowMs: Long = 0): Pair<Shelf, Subscription> {
    val same = same(s)
    val put = s.copy(id = same?.id ?: newId(), analyzed = same?.analyzed ?: s.analyzed, usedAtMs = nowMs)
    val list = if (same == null) items + put else items.map { if (it.id == put.id) put else it }
    return Shelf(list, put.id) to put
}

/** Та же подписка, что [s], если она уже в списке — тем же правилом, что [put]. */
fun Shelf.same(s: Subscription): Subscription? = items.firstOrNull { it.sameAs(s) }

/** Текущая — [id] (стала ею в момент [nowMs]); нет такой в списке — ничего. */
fun Shelf.chosen(id: String, nowMs: Long = 0): Shelf =
    if (items.any { it.id == id }) Shelf(items.map { if (it.id == id) it.copy(usedAtMs = nowMs) else it }, id) else this

fun Shelf.updated(id: String, f: (Subscription) -> Subscription): Shelf = copy(items = items.map { if (it.id == id) f(it) else it })

/** Без подписки [id]; была текущей — текущей нет (выбирает человек). */
fun Shelf.without(id: String): Shelf = Shelf(items.filter { it.id != id }, currentId.takeIf { it != id })

private fun Subscription.sameAs(other: Subscription): Boolean =
    if (link != null || other.link != null) link == other.link else text == other.text
