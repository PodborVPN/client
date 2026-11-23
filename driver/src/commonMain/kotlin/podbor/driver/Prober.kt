package podbor.driver

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import podbor.core.ProbeSpec
import podbor.core.Reach
import podbor.core.Server
import podbor.core.Carrier
import podbor.core.carrier
import podbor.core.Verdict

/** Срок одной пробы сервиса и TCP до сервера — один на все виды проб. */
const val PROBE_DEADLINE_MS = 3_000L

/** Единственное место ветвления по виду пробы; TCP до сервера снимается параллельно пробе. */
class ProberImpl(
    private val telegram: TelegramProbe,
    private val instagram: InstagramProbe,
    private val reach: TcpReach,
    private val https: suspend (Int) -> Boolean = { false },
) : Prober {
    override suspend fun probe(spec: ProbeSpec, server: Server, socksPort: Int): Verdict = coroutineScope {
        val connection = async { connectionCheck(server) }
        when (spec) {
            ProbeSpec.Telegram -> {
                val answered = async { telegram.probe(socksPort) }
                classify(answered.await(), connection.await())
            }
            ProbeSpec.Https -> {
                val answered = async { https(socksPort) }.await()
                classifyHttps(answered, connection.await())
            }
            ProbeSpec.Instagram -> {
                val answer = async { instagram.probe(socksPort) }
                classifyInstagram(answer.await(), connection.await())
            }
        }
    }

    private suspend fun connectionCheck(server: Server): EndpointCheck = when (server.transport.carrier) {
        Carrier.Udp, Carrier.Unknown -> EndpointCheck.NotApplicable
        Carrier.Tcp ->
            EndpointCheck.Tcp(reach.reach(server.endpoint, PROBE_DEADLINE_MS))
    }
}
