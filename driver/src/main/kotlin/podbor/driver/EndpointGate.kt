package podbor.driver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import podbor.core.Endpoint

/**
 * Потолок одновременных проб на IP:порт для всех трубопроводов: [perEndpoint] = 1, как Budget.
 * Ожидающие с [priority] (трубопровод общего выхода — его ждёт весь трафик) проходят раньше прочих; внутри класса —
 * по приходу. Уже идущую пробу приоритет не прерывает.
 * Карта растёт по числу адресов; одно нажатие касается ограниченного набора — чистить её незачем.
 */
class EndpointGate(private val perEndpoint: Int = 1) {
    private val lock = Any()
    private val slots = HashMap<Endpoint, Slot>()

    /** Занятые места адреса и очереди ожидающих; место освободившегося передаётся ожидающему напрямую. */
    private class Slot {
        var busy = 0
        val first = ArrayDeque<CompletableDeferred<Unit>>()
        val rest = ArrayDeque<CompletableDeferred<Unit>>()
    }

    suspend fun <T> withEndpoint(e: Endpoint, priority: Boolean = false, block: suspend () -> T): T {
        acquire(e, priority)
        try {
            return block()
        } finally {
            release(e)
        }
    }

    private suspend fun acquire(e: Endpoint, priority: Boolean) {
        val wait = synchronized(lock) {
            val slot = slots.getOrPut(e) { Slot() }
            if (slot.busy < perEndpoint) { slot.busy++; null }
            else CompletableDeferred<Unit>().also { (if (priority) slot.first else slot.rest).addLast(it) }
        } ?: return
        try {
            wait.await()
        } catch (c: CancellationException) {
            // Ещё в очереди — просто уйти; уже вручено место (вручение под замком) — вернуть его следующему.
            val handed = synchronized(lock) { slots.getValue(e).let { !it.first.remove(wait) && !it.rest.remove(wait) } }
            if (handed) release(e)
            throw c
        }
    }

    private fun release(e: Endpoint) = synchronized(lock) {
        val slot = slots.getValue(e)
        val next = slot.first.removeFirstOrNull() ?: slot.rest.removeFirstOrNull()
        if (next == null) slot.busy-- else next.complete(Unit)
    }
}
