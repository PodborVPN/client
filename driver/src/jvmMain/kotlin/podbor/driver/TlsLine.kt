package podbor.driver

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.fx.coroutines.parMap
import arrow.fx.coroutines.parZip
import arrow.fx.coroutines.resourceScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import podbor.core.DNS_FAILURE
import podbor.core.Handshake
import podbor.core.LineObservation
import podbor.core.Nel
import podbor.core.Sample
import podbor.core.Stage
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

private val RESET_MARKS = listOf("reset", "broken pipe", "terminated the handshake")

/**
 * Чистая карта исключения фазы в исход линии. waitedMs — сколько прождали на этой фазе;
 * allowedMs и beforeMs — факты фазы для Silent (см. Handshake.Silent).
 */
fun handshakeOf(stage: Stage, error: Throwable, waitedMs: Long, allowedMs: Long? = null, beforeMs: Long? = null): Handshake {
    val message = error.message.orEmpty()
    return when {
        error is SocketTimeoutException -> Handshake.Silent(stage, waitedMs, allowedMs, beforeMs)
        error is NoRouteToHostException -> Handshake.NoRoute
        error is ConnectException -> when {
            message.contains("refused", ignoreCase = true) -> Handshake.Refused
            message.contains("unreachable", ignoreCase = true) -> Handshake.NoRoute
            else -> Handshake.Unknown(error.javaClass.simpleName)
        }
        // Сертификат не тот (подмена или чужой отпечаток) — причина лежит в цепочке, а не в самом SSLException.
        error is SSLException && generateSequence<Throwable>(error) { it.cause }.any { it is CertificateException } -> Handshake.Mirage
        (error is SSLException || error is SocketException) && RESET_MARKS.any { message.contains(it, ignoreCase = true) } ->
            Handshake.Reset(stage)
        error is UnknownHostException -> Handshake.Unknown(DNS_FAILURE)
        error is SecurityException -> Handshake.Unknown("protect")
        else -> Handshake.Unknown(error.javaClass.simpleName)
    }
}

/** Доверяет единственному листовому сертификату с заданным отпечатком; всё прочее — CertificateException (→ Mirage). */
internal class PinnedTrust(private val sha256: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("клиентские сертификаты не принимаем")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty() || !pinMatches(sha256, chain[0].encoded)) throw CertificateException("отпечаток не совпал")
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Где сейчас проба — видно ожидающей стороне: по потолку исход берётся по фазе, а не «не знаю».
 * allowedMs и beforeMs — факты фазы, которые потолок кладёт в Silent.
 */
private sealed interface Progress {
    data object Resolving : Progress
    data class Connecting(val sinceNanos: Long, val allowedMs: Long, val beforeMs: Long) : Progress
    data class Negotiating(val sinceNanos: Long, val allowedMs: Long, val beforeMs: Long) : Progress
}

/**
 * Настоящее TLS-рукопожатие мимо туннеля: connect ≤ connectMs, весь вызов ≤ ceilingMs.
 * Потолок отдаёт исход по фазе, на которой застал пробу: TLS — Silent(Tls, сколько ждали ServerHello)
 * (подпись: TCP есть, данные мертвы, RST нет), connect — Silent(Connect, сколько ждали SYN/ACK),
 * резолв — Unknown("ceiling") (незнание не улика). soTimeout чтения — остаток потолка от начала пробы
 * (не меньше 300 мс), поэтому чтение и потолок кончаются вместе, а не наперегонки.
 * connectMs меряется без резолва имени: сначала resolve, потом connect по адресу. Мимо туннеля на Android — потому что
 * приложение исключено из своего VPN (addDisallowedApplication).
 *
 * Блокирующая часть (connect, рукопожатие) — дочерняя корутина вызывающего на IO; сокет — ресурс Arrow, его release
 * (close) размыкает connect и read при потолке и при отмене вызывающего, после чего дочерняя доживает мгновенно.
 * soTimeout действует на каждое чтение отдельно, поэтому от сервера, отдающего рукопожатие по частям, защищает только потолок.
 */
class TlsHandshaker(
    private val connectMs: Long = 1_500,
    private val ceilingMs: Long = 1_800,
    private val newSocket: () -> Socket = ::Socket,
    /**
     * Резолв имени — отдельно от connect, чтобы connectMs (RTT опоры для порога тишины) не включал DNS.
     * По умолчанию — блокирующий getByName на пуле IO, ожидание которого отменяемо: зависший резолвер
     * потолок не держит, его поток брошен и доживает сам (таймаут резолвера ОС).
     */
    private val resolve: suspend (String) -> InetAddress = { host ->
        CompletableFuture.supplyAsync({ InetAddress.getByName(host) }, Dispatchers.IO.asExecutor()).await()
    },
    /** TLS-рукопожатие поверх подключённого сокета; шов для тестов без настоящего сервера. */
    private val negotiate: (Socket, Reference) -> Unit = ::startTls,
) : Handshaker {

    override suspend fun shake(ref: Reference): Handshake {
        val started = System.nanoTime()
        val progress = AtomicReference<Progress>(Progress.Resolving)
        return withTimeoutOrNull(ceilingMs) { attempt(ref, started, progress) } ?: onCeiling(progress.get())
    }

    private fun onCeiling(p: Progress): Handshake = when (p) {
        Progress.Resolving -> Handshake.Unknown("ceiling")
        is Progress.Connecting -> Handshake.Silent(Stage.Connect, sinceMs(p.sinceNanos), p.allowedMs, p.beforeMs)
        is Progress.Negotiating -> Handshake.Silent(Stage.Tls, sinceMs(p.sinceNanos), p.allowedMs, p.beforeMs)
    }

    private suspend fun attempt(ref: Reference, started: Long, progress: AtomicReference<Progress>): Handshake =
        Either.catch { resolve(ref.host) }.fold(
            { handshakeOf(Stage.Connect, it, 0) }, // DNS -> Unknown("dns"); обрыв резолвера тоже значение
            { address ->
                // Порядок важен: resourceScope внутри coroutineScope — при отмене сокет закрывается раньше,
                // чем coroutineScope ждёт дочернюю, иначе ожидание висело бы на заблокированном connect.
                coroutineScope {
                    resourceScope {
                        Either.catch { install({ newSocket() }) { raw, _ -> Either.catch { raw.close() } } }.fold(
                            { handshakeOf(Stage.Connect, it, 0) },
                            { raw -> async(Dispatchers.IO) { shakeBlocking(raw, address, ref, started, progress) }.await() },
                        )
                    }
                }
            },
        )

    /**
     * Каждая фаза — под своим Either.catch: что бы ни бросил сокет, это исход-значение с фазой и временем ожидания.
     * Между фазами — ensureActive: потолок отменяет эту корутину раньше, чем release закрывает сокет, поэтому
     * connect, вернувшийся из-за close(), не переводит фазу в Tls и потолок видит ту фазу, на которой застал пробу.
     */
    private fun CoroutineScope.shakeBlocking(raw: Socket, address: InetAddress, ref: Reference, started: Long, progress: AtomicReference<Progress>): Handshake {
        val resolved = sinceMs(started)
        val connecting = Progress.Connecting(System.nanoTime(), minOf(connectMs, left(resolved)), resolved).also(progress::set)
        return Either.catch { raw.connect(InetSocketAddress(address, ref.port), connectMs.toInt()) }.fold(
            { handshakeOf(Stage.Connect, it, sinceMs(connecting.sinceNanos), connecting.allowedMs, connecting.beforeMs) },
            {
                val connected = sinceMs(connecting.sinceNanos)
                ensureActive()
                val before = sinceMs(started)
                val hello = maxOf(ceilingMs - before, HELLO_FLOOR_MS)
                val negotiating = Progress.Negotiating(System.nanoTime(), minOf(hello, left(before)), before).also(progress::set)
                Either.catch {
                    raw.soTimeout = hello.toInt()
                    negotiate(raw, ref)
                }.fold(
                    { handshakeOf(Stage.Tls, it, sinceMs(negotiating.sinceNanos), negotiating.allowedMs, negotiating.beforeMs) },
                    { Handshake.Ok(connected) },
                )
            },
        )
    }

    private fun sinceMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** Остаток потолка, если от начала пробы прошло [elapsedMs]. */
    private fun left(elapsedMs: Long): Long = (ceilingMs - elapsedMs).coerceAtLeast(0)

    private companion object {
        const val HELLO_FLOOR_MS = 300L
    }
}

/** Настоящее TLS-рукопожатие поверх подключённого сокета: SNI, проверка системная или по отпечатку. */
private fun startTls(raw: Socket, ref: Reference) {
    val factory: SSLSocketFactory = when (val trust = ref.trust) {
        Trust.System -> SSLSocketFactory.getDefault() as SSLSocketFactory
        is Trust.Pinned -> SSLContext.getInstance("TLS").apply { init(null, arrayOf(PinnedTrust(trust.sha256)), SecureRandom()) }.socketFactory
    }
    (factory.createSocket(raw, ref.sni, ref.port, false) as SSLSocket).use { tls ->
        tls.sslParameters = tls.sslParameters.apply {
            serverNames = listOf(SNIHostName(ref.sni))
            if (ref.trust == Trust.System) endpointIdentificationAlgorithm = "HTTPS"
        }
        tls.startHandshake()
    }
}

/** Линия: все эталоны обеих сторон параллельно, раунд ≈ самой медленной пробе, не сумме. */
class LineProbeImpl(
    private val russia: Nel<Reference> = RUSSIA_REFS,
    private val abroad: Nel<Reference> = ABROAD_REFS,
    private val shaker: Handshaker = TlsHandshaker(),
    /** Свежие эталоны — новые имена каждый раунд ([freshRefs]); они среди российских: адрес — Яндекс DNS. */
    private val fresh: () -> List<Reference> = { freshRefs() },
) : LineProbe {
    override suspend fun observe(): LineObservation =
        parZip({ round(russia + fresh()) }, { round(abroad) }) { ru, far -> LineObservation(ru, far) }

    private suspend fun round(refs: Nel<Reference>): Nel<Sample> =
        refs.toList().parMap { Sample(it.label, shaker.shake(it), it.role, it.lookup) }.let { NonEmptyList(it.first(), it.drop(1)) }
}
