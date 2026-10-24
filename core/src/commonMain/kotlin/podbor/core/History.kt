package podbor.core

/** Как событие истории: хорошо (работает, нашёл), плохо (мёртв, не нашёл) или просто сведения. */
enum class Mood { Good, Bad, Info }

/** Событие истории сервиса в шторке: когда ([atMs], настенное), как и что — словами экрана. */
data class HistoryEvent(val atMs: Long, val mood: Mood, val text: String)
