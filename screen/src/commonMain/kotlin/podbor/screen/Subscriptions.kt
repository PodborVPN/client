package podbor.screen

import arrow.core.Nel
import kotlinx.serialization.Serializable

/** Анализ подписки: не было; прошёл целиком; прерван — проверено [Interrupted.done] из [Interrupted.total]. */
@Serializable
sealed interface Analyzed {
    @Serializable data object Never : Analyzed
    @Serializable data class Done(val atMs: Long) : Analyzed
    @Serializable data class Interrupted(val atMs: Long, val done: Int, val total: Int) : Analyzed
}

/** Срок подписки по словам провайдера: не сказал (или бессрочно); оплачена до; кончилась; кончился трафик. */
sealed interface Term {
    data object Unknown : Term
    data class Until(val atS: Long) : Term
    data class Expired(val atS: Long) : Term
    data object OutOfTraffic : Term
}

/** Подписка в списке глазами экрана — без ссылки и текста (секрет): имя, серверов, анализ, срок, сведения провайдера. */
data class SubscriptionCard(
    val id: String,
    val title: String,
    val servers: Int,
    val analyzed: Analyzed,
    val term: Term,
    val note: SubscriptionNote,
)

/**
 * Подписки человека: нет ни одной; есть, но текущей нет (её удалили — выбирает сам); текущая и прочие. Текущая не из
 * списка — непостроима.
 */
sealed interface Subscriptions {
    data object None : Subscriptions
    data class Unchosen(val list: Nel<SubscriptionCard>) : Subscriptions
    data class Chosen(val current: SubscriptionCard, val others: List<SubscriptionCard>) : Subscriptions
}

/** Карточки списка, текущая первой. */
fun Subscriptions.cards(): List<SubscriptionCard> = when (this) {
    Subscriptions.None -> emptyList()
    is Subscriptions.Unchosen -> list
    is Subscriptions.Chosen -> listOf(current) + others
}

/** Текущая ли подписка [id]. */
fun Subscriptions.isCurrent(id: String): Boolean = when (this) {
    Subscriptions.None, is Subscriptions.Unchosen -> false
    is Subscriptions.Chosen -> current.id == id
}

/** Выбор подписки после её обновления: можно ехать; провайдер говорит, что она кончилась ([Expired.support] — к нему). */
sealed interface Picked {
    data object Ready : Picked
    data class Expired(val support: Support) : Picked
}
