package podbor.screen

/** Что человек вставил: ссылку на подписку или сами конфиги текстом. Содержимое секретно — в логи не идёт. */
sealed interface SubscriptionInput {
    data class Link(val url: String) : SubscriptionInput
    data class Text(val text: String) : SubscriptionInput
}

sealed interface InputError {
    data object Empty : InputError
    data object Unreadable : InputError
    /** [whitelist] — скачать не вышло, а сеть похожа на «белый» режим. */
    data class Download(val whitelist: Boolean) : InputError
    data object NoServers : InputError
    /** Серверы есть, а Xray анализа не поднялся (конфиг или движок). */
    data object ScanFailed : InputError
}

/** Что показать о подписке: имя, строки сведений, ссылка поддержки, объявление провайдера. */
data class SubscriptionNote(
    val title: String?,
    val lines: List<String>,
    val supportUrl: String?,
    val announce: String?,
)
