package podbor.driver

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import podbor.core.Letter

/**
 * Одна линия на нажатие: запросы во время замера сливаются в один, а каждая LineObserved уходит во все подписанные ящики.
 * Запрос после конца замера начинает новый. [scope] — область проб нажатия; запросы приходят из разных корутин актора.
 *
 * Ящики должны быть безразмерными (Channel.UNLIMITED, как у актора): доставка — trySend, не приостанавливается и
 * идёт под замком вместе со сбросом «занято», поэтому новый замер не начнётся до конца рассылки, а порядок букв
 * в каждом ящике равен порядку замеров. Закрытый или отменённый ящик пропускается, остальные не страдают.
 * Отписки нет: ящики живут одно нажатие. Сбой замера (не отмена) глотается: хаб сбрасывается, следующий запрос мерит заново.
 */
class LineHub(private val line: LineProbe, private val clock: Clock, private val scope: CoroutineScope) {
    private val lock = SynchronizedObject()
    private val inboxes = mutableListOf<SendChannel<Letter>>()
    private var running = false

    fun subscribe(inbox: SendChannel<Letter>) {
        synchronized(lock) { inboxes += inbox }
    }

    fun request() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        scope.launch {
            var letter: Letter? = null
            try {
                letter = Letter.LineObserved(clock.nowMs(), line.observe())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Замер упал вопреки контракту: не валим область нажатия, письма не будет.
            } finally {
                synchronized(lock) {
                    if (letter != null) for (inbox in inboxes) inbox.trySend(letter)
                    running = false
                }
            }
        }
    }
}
