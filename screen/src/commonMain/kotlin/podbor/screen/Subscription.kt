package podbor.screen

import kotlinx.serialization.Serializable
import podbor.core.Button

/** Что человек вставил: ссылку на подписку или сами конфиги текстом. Содержимое секретно — в логи не идёт. */
sealed interface SubscriptionInput {
    data class Link(val url: String) : SubscriptionInput
    data class Text(val text: String) : SubscriptionInput
}

/** Поддержка провайдера: ссылки нет или есть (`support-url`, только http/https). */
@Serializable
sealed interface Support {
    @Serializable data object None : Support
    @Serializable data class Link(val url: String) : Support
}

/** Объявление провайдера (`announce`): нет или его слова. */
@Serializable
sealed interface Announce {
    @Serializable data object None : Announce
    @Serializable data class Text(val text: String) : Announce
}

sealed interface InputError {
    data object Empty : InputError
    data object Unreadable : InputError
    /** Зашифрованная ссылка Happ (`happ://crypt…`): открыть её может только Happ. */
    data object HappOnly : InputError
    /** [whitelist] — скачать не вышло, а сеть похожа на «белый» режим. */
    data class Download(val whitelist: Boolean) : InputError
    /** Ни одна ссылка не стала сервером; [skipped] — какие и почему. */
    data class NoServers(val skipped: Skipped) : InputError
    /** Провайдер отказал: на подписке уже максимум устройств; [announce] — его слова, [support] — его поддержка. */
    data class DeviceLimit(val announce: Announce, val support: Support) : InputError
    /** Серверы есть, а Xray анализа не поднялся (конфиг или движок). */
    data object ScanFailed : InputError
}

/** Что показать о подписке: имя, строки сведений, поддержка, объявление провайдера. */
data class SubscriptionNote(
    val title: String?,
    val lines: List<String>,
    val support: Support,
    val announce: Announce,
)

/**
 * Поддержка на главном — когда ничего не работает: подбор кончился ничем (кнопка красная). Иначе не отвлекаем: ищем,
 * работает или ещё не нажимали.
 */
fun supportWhenStuck(button: Button, support: Support): Support = when (button) {
    Button.Red -> support
    Button.Idle, Button.Ready, Button.Searching, Button.Green -> Support.None
}
