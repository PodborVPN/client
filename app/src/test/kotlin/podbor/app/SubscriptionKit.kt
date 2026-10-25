package podbor.app

import podbor.app.sub.Shelf
import podbor.app.sub.Subscription
import podbor.app.sub.SubscriptionStore
import podbor.screen.Analyzed

/** Одна подписка — она и текущая (id «1», если свой не задан): сцена, как до списка подписок. */
internal suspend fun SubscriptionStore.saveOne(s: Subscription) {
    val one = s.copy(id = s.id.ifEmpty { "1" })
    save(Shelf(listOf(one), one.id))
}

/** Текущая без полей списка (id, ключи серверов, анализ, момент использования) — сравнивать с записью, как до списка. */
internal suspend fun SubscriptionStore.one(): Subscription? =
    current()?.copy(id = "", servers = emptyList(), analyzed = Analyzed.Never, usedAtMs = 0)
