@file:UseSerializers(NonEmptyListSerializer::class)

package podbor.core

import arrow.core.nonEmptyListOf
import arrow.core.serialization.NonEmptyListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Блок IP:порт по подписи TlsSilence держится ~10 мин (по замерам). */
const val BLOCK_TTL_MS: Long = 600_000

/** Ключ памяти сети: оператор («wifi» для Wi-Fi), сценарий и сервис; старая память без сервиса — Телега. */
@Serializable
data class MemoryKey(val operator: String, val scenario: Scenario, val service: ServiceId = Telegram.id)

/** Успех хранится по устойчивому ключу сервера: теги s001… сдвигаются при обновлении подписки. */
@Serializable
data class Success(val serverKey: String, val atMs: Long)

/**
 * Память между нажатиями. Ядро её не хранит: приходит в букве Pressed, уходит делом Remember —
 * поэтому Лента нажатия самодостаточна.
 */
@Serializable
data class Memory(
    /** Успехи по ключу, свежие первыми. */
    val successes: Map<MemoryKey, Nel<Success>>,
    /** IP:порт → момент, до которого его не трогаем. */
    val blocks: Map<Endpoint, Long>,
) {
    companion object {
        val Empty = Memory(successes = emptyMap(), blocks = emptyMap())
    }
}

fun Memory.rememberSuccess(key: MemoryKey, server: Server, atMs: Long): Memory {
    val k = server.key
    val rest = successes[key]?.toList().orEmpty().filter { it.serverKey != k }
    return copy(successes = successes + (key to nonEmptyListOf(Success(k, atMs), *rest.toTypedArray())))
}

fun Memory.block(endpoint: Endpoint, atMs: Long): Memory =
    copy(blocks = blocks + (endpoint to atMs + BLOCK_TTL_MS))

fun Memory.isBlocked(endpoint: Endpoint, nowMs: Long): Boolean =
    blocks[endpoint]?.let { nowMs < it } ?: false

/** Успехи ключей сервиса [service] — из [update], прочие — из this; блоки — объединение с бо́льшим сроком. */
fun Memory.merged(update: Memory, service: ServiceId): Memory =
    Memory(
        successes = successes.filterKeys { it.service != service } + update.successes.filterKeys { it.service == service },
        blocks = (blocks.asSequence() + update.blocks.asSequence()).groupBy({ it.key }, { it.value }).mapValues { it.value.max() },
    )
