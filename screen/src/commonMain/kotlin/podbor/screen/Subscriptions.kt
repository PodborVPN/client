package podbor.screen

import arrow.core.Nel
import podbor.core.SubscriptionFacts
import podbor.core.Traffic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Анализ подписки: не было; прошёл целиком; прерван — проверено [Interrupted.done] из [Interrupted.total]. Имена в
 * файле подписок закреплены (`@SerialName` — те, что писала 0.15): перенос или переименование класса файл не ломает.
 */
@Serializable
sealed interface Analyzed {
    @Serializable @SerialName("podbor.screen.Analyzed.Never") data object Never : Analyzed
    @Serializable @SerialName("podbor.screen.Analyzed.Done") data class Done(val atMs: Long) : Analyzed
    @Serializable @SerialName("podbor.screen.Analyzed.Interrupted") data class Interrupted(val atMs: Long, val done: Int, val total: Int) : Analyzed
}

/** Срок подписки по словам провайдера: не сказал (или бессрочно); оплачена до; кончилась; кончился трафик. */
sealed interface Term {
    data object Unknown : Term
    data class Until(val atS: Long) : Term
    data class Expired(val atS: Long) : Term
    data object OutOfTraffic : Term
}

/**
 * Подписка в списке глазами экрана — без ссылки и текста (секрет): имя, серверов, анализ, срок, сведения провайдера;
 * как она ведёт себя на этом телефоне ([facts]) и сколько прошло через VPN на ней ([viaPodbor]).
 */
data class SubscriptionCard(
    val id: String,
    val title: String,
    val servers: Int,
    val analyzed: Analyzed,
    val term: Term,
    val note: SubscriptionNote,
    val facts: SubscriptionFacts = SubscriptionFacts.Empty,
    val viaPodbor: Traffic = Traffic.Zero,
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

/**
 * Шторка подписки на странице подписок: карточка ([Card.current] — текущая, «выбрать» ей не нужно); подтверждение
 * удаления ([Confirm.stopsVpn] — на ней сейчас VPN); выбор идёт (сервис обновляет подписку); выбор не вышел — кончилась.
 */
sealed interface SubSheet {
    data class Card(val card: SubscriptionCard, val current: Boolean) : SubSheet
    data class Confirm(val card: SubscriptionCard, val stopsVpn: Boolean) : SubSheet
    data class Picking(val card: SubscriptionCard) : SubSheet
    data class Ended(val card: SubscriptionCard, val support: Support) : SubSheet
}

/** Вставленное человеком: новая подписка или уже есть в списке (та же ссылка, а без ссылки — тот же текст). */
sealed interface Known {
    data object New : Known
    data class Saved(val card: SubscriptionCard) : Known
}

/** Чья шторка: карточка подписки на любом шаге. */
fun SubSheet.card(): SubscriptionCard = when (this) {
    is SubSheet.Card -> card
    is SubSheet.Confirm -> card
    is SubSheet.Picking -> card
    is SubSheet.Ended -> card
}

/** Выбор подписки после её обновления: можно ехать; провайдер говорит, что она кончилась ([Expired.support] — к нему). */
sealed interface Picked {
    data object Ready : Picked
    data class Expired(val support: Support) : Picked
}
