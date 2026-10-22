package podbor.driver

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import podbor.core.ProbeSpec
import podbor.core.Server
import podbor.core.Verdict

/** Срок одной пробы сервиса и TCP до сервера — один на все виды проб. */
const val PROBE_DEADLINE_MS = 3_000L

/** Единственное место ветвления по виду пробы; TCP до сервера снимается параллельно пробе. */
class ProberImpl(
    private val telegram: TelegramProbe,
    private val instagram: InstagramProbe,
    private val reach: TcpReach,
) : Prober {
    override suspend fun probe(spec: ProbeSpec, server: Server, socksPort: Int): Verdict = coroutineScope {
        val tcp = async { reach.reach(server.endpoint, PROBE_DEADLINE_MS) }
        when (spec) {
            ProbeSpec.Telegram -> {
                val answered = async { telegram.probe(socksPort) }
                classify(answered.await(), tcp.await())
            }
            ProbeSpec.Instagram -> {
                val answer = async { instagram.probe(socksPort) }
                classifyInstagram(answer.await(), tcp.await())
            }
        }
    }
}
