package podbor.driver

import arrow.core.Either
import arrow.fx.coroutines.ResourceScope
import com.google.protobuf.ByteString
import com.xray.app.router.command.OverrideBalancerTargetRequest
import com.xray.app.router.command.RoutingContext
import com.xray.app.router.command.RoutingServiceGrpcKt
import com.xray.app.router.command.TestRouteRequest
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpcKt
import com.xray.common.net.Network
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.net.InetAddress

/**
 * RoutingService Xray по gRPC: вход в РФ — TestRoute против его же правила geoip:ru; выход — OverrideBalancerTarget.
 * Канал — ресурс области: закрывается при выходе из неё, что бы с областью ни случилось.
 */
suspend fun ResourceScope.xrayApi(open: () -> ManagedChannel): XrayApi = xrayApiOn(xrayChannel(open))

/** Канал API Xray — ресурс области: один на RoutingService и StatsService сессии. */
suspend fun ResourceScope.xrayChannel(open: () -> ManagedChannel): ManagedChannel =
    install({ open() }, { c, _ -> c.shutdownNow() })

fun xrayApiOn(channel: ManagedChannel): XrayApi = GrpcXrayApi(channel)

/** StatsService по тому же каналу: все счётчики разом, без сброса — значения накопленные. */
fun xrayStatsOn(channel: ManagedChannel): XrayStats {
    val stub = StatsServiceGrpcKt.StatsServiceCoroutineStub(channel)
    return XrayStats {
        Either.catch {
            stub.queryStats(QueryStatsRequest.newBuilder().setPattern("").setReset(false).build()).statList.associate { it.name to it.value }
        }.mapLeft { EngineError.Failed("QueryStats: ${it.message}") }
    }
}

/** API на loopback-порту ([ApiListen.Tcp]) — JVM на десктопе. */
suspend fun ResourceScope.xrayApi(port: Int, host: String = "127.0.0.1"): XrayApi =
    xrayApi { ManagedChannelBuilder.forAddress(host, port).usePlaintext().build() }

private class GrpcXrayApi(channel: ManagedChannel) : XrayApi {
    private val stub = RoutingServiceGrpcKt.RoutingServiceCoroutineStub(channel)

    override suspend fun routeOf(ip: String): Either<EngineError, String> = Either.catch {
        val context = RoutingContext.newBuilder()
            .setNetwork(Network.TCP)
            .addTargetIPs(ByteString.copyFrom(InetAddress.getByName(ip).address))
            .setTargetPort(443)
        stub.testRoute(TestRouteRequest.newBuilder().setRoutingContext(context).addFieldSelectors("outbound").build()).outboundTag
    }.mapLeft { EngineError.Failed("TestRoute: ${it.message}") }

    override suspend fun setExit(balancer: String, tag: String): Either<EngineError, Unit> = overrideExit(balancer, tag)

    // Пустая цель — так Xray сам снимает override (Router.SetOverrideTarget, balancing.go: override.Get() == "").
    override suspend fun clearExit(balancer: String): Either<EngineError, Unit> = overrideExit(balancer, "")

    private suspend fun overrideExit(balancer: String, target: String): Either<EngineError, Unit> = Either.catch {
        stub.overrideBalancerTarget(OverrideBalancerTargetRequest.newBuilder().setBalancerTag(balancer).setTarget(target).build())
        Unit
    }.mapLeft { EngineError.Failed("OverrideBalancerTarget $balancer: ${it.message}") }
}
