package podbor.driver

import arrow.core.Either
import arrow.fx.coroutines.ResourceScope
import kotlinx.serialization.json.JsonObject
import podbor.core.Endpoint
import podbor.core.LineObservation
import podbor.core.Memory
import podbor.core.TrafficBook
import podbor.core.Passport
import podbor.core.ProbeSpec
import podbor.core.Reach
import podbor.core.Server
import podbor.core.Verdict
import kotlin.concurrent.Volatile

/** Ошибки движка — системные, живут на краю; ядро о них не знает. */
sealed interface EngineError {
    data class Failed(val what: String) : EngineError
}

/** Подписка → outbound'ы Xray (конвертер libXray). Ссылки разбирает он, не мы. */
fun interface LinkConverter {
    suspend fun convert(text: String): Either<EngineError, List<JsonObject>>
}

/**
 * Xray как ресурс области: run запускает его и ставит остановку в `release` того же `install` — Xray гаснет
 * при выходе из области, в том числе на исключении или отмене; отдельного stop() нет. Left — не поднялся
 * (недоступное тоже гасится: полузапущенный процесс не остаётся).
 */
fun interface XrayEngine {
    suspend fun ResourceScope.run(config: JsonObject): Either<EngineError, Unit>
}

interface XrayApi {
    /** Через какой outbound Xray повёл бы IP: "direct" ⟺ сработало правило geoip:ru. */
    suspend fun routeOf(ip: String): Either<EngineError, String>

    /** OverrideBalancerTarget(balancer, tag): общий балансер "exit" или балансер сервиса "svc.<id>". */
    suspend fun setExit(balancer: String, tag: String): Either<EngineError, Unit>

    /** OverrideBalancerTarget(balancer, "") — пустая цель снимает выход: балансировщик Xray снова выбирает сам. */
    suspend fun clearExit(balancer: String): Either<EngineError, Unit>
}

/** Сколько DC Telegram ответили resPQ на блоб через SOCKS-inbound кандидата. */
/** Итоги трафика между запусками процесса: «на этой сети» и «через VPN на этой подписке». */
interface TrafficBookStore {
    fun load(): TrafficBook
    fun save(b: TrafficBook)
}

/** Счётчики Xray (`StatsService.QueryStats`, без сброса): имя счётчика → накопленные байты. */
fun interface XrayStats {
    suspend fun query(): Either<EngineError, Map<String, Long>>
}

fun interface TelegramProbe {
    suspend fun probe(socksPort: Int): Int
}

/** Ответ корзины Инсты: код и прочитанный объём CDN (null — нет ответа), код www (null — нет ответа). */
data class InstagramAnswer(val cdnStatus: Int?, val cdnBytes: Int, val wwwStatus: Int?)

/** Проба Инсты через SOCKS-inbound кандидата: CDN-файл и www. */
fun interface InstagramProbe {
    suspend fun probe(socksPort: Int): InstagramAnswer
}

/** Запуск проб: вид пробы берёт из ProbeSpec сервиса, вердикт отдаёт готовый (с учётом TCP). */
fun interface Prober {
    suspend fun probe(spec: ProbeSpec, server: Server, socksPort: Int): Verdict
}

/** TCP-connect к серверу кандидата мимо туннеля: через SOCKS Xray его не видно. */
fun interface TcpReach {
    suspend fun reach(endpoint: Endpoint, deadlineMs: Long): Reach
}

/** Имя хоста сервера → IP (до классификации входа). null — не разрешилось. */
fun interface Resolver {
    suspend fun resolve(host: String): String?
}

/**
 * Линия: один раунд прямых TLS-проб мимо туннеля.
 * Контракт: observe() всегда возвращает полный раунд в пределах потолка пробы — исход каждого эталона это значение
 * (Handshake), а не исключение; кидать можно только отмену. Машина нажатия ждёт подтверждающий раунд при подозрении
 * и не имеет другого выхода, кроме 60-секундного таймаута нажатия, — потерянный раунд стоил бы минуты.
 */
fun interface LineProbe {
    suspend fun observe(): LineObservation
}

fun interface Clock {
    fun nowMs(): Long
}

interface Store {
    fun loadMemory(): Memory
    fun saveMemory(m: Memory)
}

/** Паспорт подписки между анализами; null — анализа ещё не было (или файл не читается). */
interface PassportStore {
    fun load(): Passport?
    fun save(p: Passport)
}

/** Паспорт в памяти процесса: для разовых прогонов (CLI) и тестов; приложение держит файловый (FilePassportStore). */
class InMemoryPassportStore : PassportStore {
    @Volatile private var passport: Passport? = null
    override fun load() = passport
    override fun save(p: Passport) { passport = p }
}

fun interface TapeSink {
    fun write(line: String)
}

/** Внешний IP выхода кандидата: GET к своему эталону через SOCKS-inbound кандидата. null — нет ответа или не IP. */
fun interface ExitProbe {
    suspend fun exitIp(socksPort: Int): String?
}
