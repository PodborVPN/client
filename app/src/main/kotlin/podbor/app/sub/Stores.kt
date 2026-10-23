package podbor.app.sub

import android.util.Log
import arrow.core.Either
import arrow.core.getOrElse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import podbor.core.Passport
import podbor.core.TrafficBook
import podbor.driver.PassportStore
import podbor.driver.TrafficBookStore
import java.io.File
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подписка человека: ссылка (если вставил ссылку) и скачанный/вставленный текст. Секрет — в логи и Ленту не пишется.
 * [fetchedAtMs] — когда текст последний раз скачан по ссылке; [updateHours] — интервал обновления от провайдера;
 * [provider] — что провайдер сказал о подписке; [fallbackUrl] — его запасная ссылка, когда основная не скачивается.
 */
@Serializable
data class Subscription(
    val link: String?,
    val text: String,
    val fetchedAtMs: Long? = null,
    val updateHours: Int? = null,
    val provider: ProviderInfo? = null,
    val fallbackUrl: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

/** Читает файл как T; нет файла или он битый — null, без исключений. Содержимое в лог не попадает. */
private fun <T> readJson(file: File, read: (String) -> T): T? =
    Either.catch { read(file.readText()) }.getOrElse { null }

/** Пишет атомарно ([podbor.app.net.writeAtomic]); сбой логируется (без содержимого) и не пробрасывается. */
private fun writeAtomic(file: File, what: String, text: String, warn: (String) -> Unit) {
    podbor.app.net.writeAtomic(file, text).onLeft { warn("$what не записан: ${it.javaClass.simpleName}") }
}

class SubscriptionStore(
    dir: File,
    private val warn: (String) -> Unit = { Log.w("Podbor", it) },
    /** Пул файла; тест подставляет свой диспетчер. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(dir, "subscription.json")
    /** Файл — на пуле IO: зовут и с главного потока (экран). */
    suspend fun load(): Subscription? = withContext(io) {
        readJson(file) { json.decodeFromString(Subscription.serializer(), it) }
    }
    suspend fun save(s: Subscription) = withContext(io) {
        writeAtomic(file, "подписка", json.encodeToString(Subscription.serializer(), s), warn)
    }
}

class FilePassportStore(dir: File, private val warn: (String) -> Unit = { Log.w("Podbor", it) }) : PassportStore {
    private val file = File(dir, "passport.json")
    override fun load(): Passport? = readJson(file) { json.decodeFromString(Passport.serializer(), it) }
    override fun save(p: Passport) = writeAtomic(file, "паспорт", json.encodeToString(Passport.serializer(), p), warn)
}

/** Итоги трафика (на сети, через VPN на подписке) между запусками процесса; нет файла или битый — пустые. */
class FileTrafficBookStore(dir: File, private val warn: (String) -> Unit = { Log.w("Podbor", it) }) : TrafficBookStore {
    private val file = File(dir, "traffic.json")
    override fun load(): TrafficBook = readJson(file) { json.decodeFromString(TrafficBook.serializer(), it) } ?: TrafficBook()
    override fun save(b: TrafficBook) = writeAtomic(file, "итоги трафика", json.encodeToString(TrafficBook.serializer(), b), warn)
}

/**
 * Ключ итога «через VPN на этой подписке»: по ссылке (обновление текста по ссылке итог не сбрасывает), без ссылки — по
 * тексту. 8 байт HMAC-SHA256 с солью установки ([installSalt]), hex: в Ленту идёт ключ, не ссылка, и по ключу не
 * проверить догадку «это такая-то публичная ссылка» и не связать Ленты разных установок.
 */
fun subscriptionKey(s: Subscription, salt: ByteArray): String =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal((s.link ?: s.text).encodeToByteArray())
        .take(8).toByteArray().hex()

/**
 * Номер устройства для панели провайдера (`x-hwid`): HMAC на своём ключе (метка «hwid» + соль установки), 16 байт hex —
 * стабилен, пока живут данные приложения; ключ не тот, что у [subscriptionKey], поэтому ни с одним ключом подписки не
 * совпадёт и соль по нему не восстановить. Формат панелей: 10–64 знака `[A-Za-z0-9=-]`, иначе заголовок молча не
 * засчитывается.
 */
fun hwidOf(salt: ByteArray): String =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("hwid".encodeToByteArray() + salt, "HmacSHA256")) }
        .doFinal("device".encodeToByteArray())
        .take(16).toByteArray().hex()

/** Заголовки устройства при скачивании подписки — так панели (Remnawave и др.) считают устройства на подписке. */
fun deviceHeaders(hwid: String, osVersion: String, model: String): Map<String, String> = mapOf(
    "x-hwid" to hwid,
    "x-device-os" to "Android",
    "x-ver-os" to osVersion,
    "x-device-model" to model,
)

private const val SALT_BYTES = 16

/**
 * Соль установки: 16 случайных байт (в файле — hex), создаются при первом вопросе и живут, пока живут данные приложения.
 * Не читается — новая (итог VPN начнётся заново, а не сломается); не записалась — новая и в следующий раз.
 */
fun installSalt(dir: File): ByteArray {
    val file = File(dir, "install-salt")
    val stored = Either.catch { file.readText().trim() }.getOrNull()
        ?.takeIf { it.length == SALT_BYTES * 2 && it.all { c -> c in "0123456789abcdef" } }
        ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
    return stored ?: ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }.also { podbor.app.net.writeAtomic(file, it.hex()) }
}

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
