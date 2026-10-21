package podbor.app.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import arrow.core.right
import arrow.fx.coroutines.ResourceScope
import kotlinx.serialization.json.JsonObject
import podbor.driver.EngineError
import podbor.driver.TUN_MTU
import podbor.driver.XrayEngine

/** Адрес телефона в TUN и адрес DNS там же: DNS-запросы уходят в TUN, их отвечает блок `dns` Xray (правило порта 53). */
private const val TUN_ADDRESS = "10.111.0.2"
private const val TUN_PREFIX = 30
private const val TUN_DNS = "10.111.0.1"

/** fd TUN годен только > 0: на 0 (и при отсутствии env) Xray на Android молча читает stdin. */
fun tunFdOf(fd: Int): Either<EngineError, Int> = if (fd > 0) fd.right() else EngineError.Failed("tun fd $fd").left()

/**
 * TUN как ресурс области: весь IPv4 и IPv6 — в туннель, само приложение (его Xray, пробы, линия) и [bypass] — мимо.
 * Release закрывает fd приложения; Xray держит свой дубликат и гасит его в stopXray, поэтому туннель ставится
 * в область раньше Xray — освобождается позже ([tunSession]).
 */
suspend fun ResourceScope.tunnel(
    builder: VpnService.Builder,
    bypass: List<String>,
    self: String,
    log: (String) -> Unit = { Log.i("Podbor", it) },
): Either<EngineError, ParcelFileDescriptor> = install(
    { establish(builder, bypass, self) },
    { tun, _ ->
        tun.onRight { pfd ->
            Either.catch { pfd.close() }.fold({ log("fd не закрылся: ${it.javaClass.simpleName}") }, { log("fd закрыт") })
        }
    },
)

private fun establish(builder: VpnService.Builder, bypass: List<String>, self: String): Either<EngineError, ParcelFileDescriptor> = either {
    val pfd = Either.catch {
        builder.setSession("Podbor")
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, TUN_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(TUN_DNS)
        (listOf(self) + bypass).forEach { builder.addDisallowedApplication(it) }
        builder.establish()
    }.mapLeft { EngineError.Failed("VpnService.Builder бросил ${it.javaClass.simpleName}") }.bind()
    // null — у приложения нет согласия на VPN (prepare не пройден или отозван).
    ensureNotNull(pfd) { EngineError.Failed("establish: нет согласия на VPN") }
}

/**
 * Сессия VPN в одной области: сначала туннель, затем прогрев Xray на его fd. Порядок install задаёт обратный
 * порядок release: Xray остановлен → fd закрыт (иначе Xray читал бы закрытый дескриптор).
 */
suspend fun <T> ResourceScope.tunSession(
    tunnel: suspend ResourceScope.() -> Either<EngineError, Int>,
    warm: suspend ResourceScope.(tunFd: Int) -> Either<EngineError, T>,
): Either<EngineError, T> = either {
    val fd = tunFdOf(tunnel().bind()).bind()
    warm(fd).bind()
}

/**
 * Движок, сообщающий о своей остановке: отметка ставится в область раньше внутреннего install
 * и потому срабатывает после stopXray.
 */
fun XrayEngine.reporting(stopped: () -> Unit): XrayEngine {
    val inner = this
    return object : XrayEngine {
        override suspend fun ResourceScope.run(config: JsonObject): Either<EngineError, Unit> {
            onRelease { stopped() }
            return with(inner) { run(config) }
        }
    }
}
