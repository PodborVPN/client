package podbor.driver

import arrow.core.Either
import podbor.core.ServerId

enum class ExitObservation { Replied, Failed, Unknown }

fun interface OutboundObservatory {
    suspend fun read(): Either<EngineError, Map<ServerId, ExitObservation>>
}

internal fun exitObservation(alive: Boolean, samples: Long, failures: Long): ExitObservation = when {
    samples <= 0 || failures !in 0..samples -> ExitObservation.Unknown
    alive && failures < samples -> ExitObservation.Replied
    !alive && failures == samples -> ExitObservation.Failed
    else -> ExitObservation.Unknown
}
