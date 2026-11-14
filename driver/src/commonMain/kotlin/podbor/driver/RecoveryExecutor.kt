package podbor.driver

import arrow.core.Either
import podbor.core.RecoveryApplied
import podbor.core.RecoveryChange
import podbor.core.RecoveryDeed
import podbor.core.RecoveryLetter
import podbor.core.RecoveryLine
import podbor.core.RecoveryRoute
import podbor.core.ServerId

/** При каждой смене порты сверяют актуальность попытки под тем же замком, под которым меняют маршрут. */
internal interface RecoveryPorts {
    suspend fun checkLine(attempt: Long): Either<EngineError, RecoveryLine>
    suspend fun find(attempt: Long, routes: Map<RecoveryRoute, ServerId>): Either<EngineError, Map<RecoveryRoute, ServerId>>
    suspend fun apply(
        attempt: Long,
        changes: Map<RecoveryRoute, RecoveryChange>,
    ): Either<EngineError, Map<RecoveryRoute, RecoveryApplied>>
    suspend fun cancel(attempt: Long)
}

internal suspend fun executeRecovery(
    deed: RecoveryDeed,
    ports: RecoveryPorts,
    report: suspend (EngineError) -> Unit,
    send: suspend (RecoveryLetter) -> Unit,
): Unit = when (deed) {
    is RecoveryDeed.CheckLine -> {
        val result = ports.checkLine(deed.attempt).fold(
            { error ->
                report(error)
                RecoveryLine.Unknown
            },
            { it },
        )
        send(RecoveryLetter.LineChecked(deed.attempt, result))
    }
    is RecoveryDeed.Find -> {
        val result = ports.find(deed.attempt, deed.routes).fold(
            { error ->
                report(error)
                emptyMap()
            },
            { it },
        )
        send(RecoveryLetter.Candidates(deed.attempt, result))
    }
    is RecoveryDeed.Apply -> {
        val result = ports.apply(deed.attempt, deed.changes).fold(
            { error ->
                report(error)
                emptyMap()
            },
            { it },
        )
        send(RecoveryLetter.Applied(deed.attempt, result))
    }
    is RecoveryDeed.Cancel -> ports.cancel(deed.attempt)
}
