package podbor.app.vpn

import podbor.core.Blame
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.RecoveryApplied
import podbor.core.RecoveryEvidence
import podbor.core.RecoveryRoute
import podbor.core.RecoveryState
import podbor.core.ServerId
import podbor.core.ServiceId
import podbor.core.Web
import podbor.driver.serverOrNull

internal fun recoveryCards(
    state: RecoveryState,
    live: Live,
): Map<ServiceId, CardStatus> {
    fun RecoveryRoute.service() = when (this) {
        RecoveryRoute.General -> Web.id
        is RecoveryRoute.Service -> id
    }

    fun ref(id: ServerId) = live.warm.servers.firstOrNull { it.id == id }?.let { ExitRef(it.name, it.transport) }

    return when (state) {
        RecoveryState.Watching, is RecoveryState.Checking, is RecoveryState.Applying -> emptyMap()
        is RecoveryState.Searching -> state.routes.mapNotNull { (route, server) ->
            ref(server)?.let { route.service() to CardStatus.Dead(it, blameOf(state.evidence)) }
        }.toMap()
        is RecoveryState.Settled -> state.results.mapNotNull { (route, outcome) ->
            val service = route.service()
            when (outcome) {
                RecoveryApplied.Applied -> live.driver.exits.effective(service).serverOrNull()
                    ?.let(::ref)?.let { service to CardStatus.Works(it) }
                RecoveryApplied.Rejected -> null
                RecoveryApplied.Unknown -> service to CardStatus.Unknown
            }
        }.toMap()
    }
}

private fun blameOf(evidence: RecoveryEvidence): Blame = when (evidence) {
    is RecoveryEvidence.PairFailed -> Blame.ServiceThroughExit
    is RecoveryEvidence.ExitFailed -> Blame.Server
}


internal fun verifiedCards(routes: Map<RecoveryRoute, ServerId>, live: Live): Map<ServiceId, CardStatus> =
    routes.mapNotNull { (route, id) ->
        val service = when (route) {
            RecoveryRoute.General -> Web.id
            is RecoveryRoute.Service -> route.id
        }
        val server = live.warm.servers.firstOrNull { it.id == id }
        if (server == null || live.driver.exits.effective(service).serverOrNull() != id) null
        else service to CardStatus.Works(ExitRef(server.name, server.transport))
    }.toMap()
