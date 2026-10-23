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
import java.security.MessageDigest

/**
 * Подписка человека: ссылка (если вставил ссылку) и скачанный/вставленный текст. Секрет — в логи и Ленту не пишется.
 * [fetchedAtMs] — когда текст последний раз скачан по ссылке; [updateHours] — интервал обновления от провайдера;
 * [provider] — что провайдер сказал о подписке.
 */
@Serializable
data class Subscription(
    val link: String?,
    val text: String,
    val fetchedAtMs: Long? = null,
    val updateHours: Int? = null,
    val provider: ProviderInfo? = null,
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
 * тексту. 8 байт SHA-256 hex: в Ленту идёт ключ, не ссылка.
 */
fun subscriptionKey(s: Subscription): String =
    MessageDigest.getInstance("SHA-256").digest((s.link ?: s.text).encodeToByteArray())
        .take(8).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
