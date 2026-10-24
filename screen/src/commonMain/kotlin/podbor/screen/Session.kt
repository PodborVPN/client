package podbor.screen

import kotlinx.serialization.Serializable
import podbor.core.Button

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

/**
 * Системный «постоянный VPN» с блокировкой без него: включён ([On]), нет ([Off]) или система не говорит ([Unknown] —
 * Android до 10).
 */
enum class AlwaysOn { On, Off, Unknown }

/**
 * Пора ли подсказать включить Always-on: VPN поднят и уже работает (кнопка зелёная — не мешаем подбору), других шторок
 * нет, а сам он не включён или система не говорит.
 */
fun alwaysOnHintDue(alwaysOn: AlwaysOn, session: SessionView, button: Button, ask: Ask?): Boolean =
    ask == null && session == SessionView.Up && button == Button.Green && when (alwaysOn) {
        AlwaysOn.On -> false
        AlwaysOn.Off, AlwaysOn.Unknown -> true
    }

/** Откуда выход сервиса в замере открытия: выбран (нажатием в этой сессии) или восстановлен из памяти сети. */
@Serializable
enum class ExitOrigin { Chosen, Recalled }
