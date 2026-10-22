package podbor.screen

import kotlinx.serialization.Serializable

/**
 * Сессия VPN глазами экрана: снимок без ресурсов сессии (туннель, область действий) — его можно передать между
 * процессами (на iOS туннель живёт в расширении).
 */
@Serializable
sealed interface SessionView {
    @Serializable
    data object Down : SessionView

    @Serializable
    data object Starting : SessionView

    @Serializable
    data object Up : SessionView

    /** Не поднялась; [reason] — для лога, экран показывает только сам факт. */
    @Serializable
    data class Failed(val reason: String) : SessionView
}
