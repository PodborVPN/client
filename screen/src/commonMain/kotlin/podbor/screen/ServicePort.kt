package podbor.screen

import kotlinx.coroutines.flow.StateFlow
import podbor.core.Board
import podbor.core.ServiceId

/**
 * Что экрану нужно от мозга Podbor (сессия, подбор, замеры). На Android мозг — служба в том же процессе; на iOS — в
 * приложении или в расширении туннеля: значения — снимками, которые можно передать между процессами.
 */
interface ServicePort {
    val session: StateFlow<SessionView>

    /** Сводный экран ядра: сеть, кнопка, карточка на сервис. */
    val board: StateFlow<Board>

    /** Чьи выходы в замере открытия восстановлены из памяти сети. */
    val exitOrigin: StateFlow<Map<ServiceId, ExitOrigin>>

    /** Последний релиз на GitHub; не узнали — null. */
    suspend fun latestRelease(): Release?
}

/** Память обновлялки: когда спрашивали GitHub и что он ответил. Android — SharedPreferences, iOS — UserDefaults. */
interface UpdateMemory {
    fun checkedAtMs(): Long?
    fun known(): Release?
    fun remember(atMs: Long, release: Release)
}
