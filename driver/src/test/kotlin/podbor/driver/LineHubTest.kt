@file:OptIn(ExperimentalCoroutinesApi::class)

package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import podbor.core.Letter
import podbor.core.Reach

class LineHubTest : FunSpec({
    val clock = Clock { 0L }

    test("запросы во время замера сливаются в один; оба ящика получают одну и ту же букву") {
        runTest {
            var calls = 0
            val hub = LineHub({ calls++; delay(100); line(Reach.Up, Reach.Up) }, clock, backgroundScope)
            val one = Channel<Letter>(Channel.UNLIMITED)
            val two = Channel<Letter>(Channel.UNLIMITED)
            hub.subscribe(one); hub.subscribe(two)
            hub.request(); hub.request()
            val x = one.receive(); val y = two.receive()
            calls shouldBe 1
            currentTime shouldBe 100L
            (x as Letter.LineObserved) shouldBe (y as Letter.LineObserved)
            one.tryReceive().isFailure shouldBe true
        }
    }

    test("запрос после конца замера начинает новый") {
        runTest {
            var calls = 0
            val hub = LineHub({ calls++; delay(100); line(Reach.Up, Reach.Up) }, clock, backgroundScope)
            val inbox = Channel<Letter>(Channel.UNLIMITED)
            hub.subscribe(inbox)
            hub.request(); inbox.receive()
            hub.request(); inbox.receive()
            calls shouldBe 2
        }
    }

    test("отменённый ящик не лишает буквы остальных") {
        runTest {
            val hub = LineHub({ line(Reach.Up, Reach.Up) }, clock, backgroundScope)
            val dead = Channel<Letter>(Channel.UNLIMITED)
            val live = Channel<Letter>(Channel.UNLIMITED)
            dead.cancel()
            hub.subscribe(dead); hub.subscribe(live)
            hub.request()
            live.receive().shouldBeInstanceOf<Letter.LineObserved>()
        }
    }

    test("сбой замера не валит область; следующий запрос меряет заново") {
        runTest {
            var calls = 0
            val hub = LineHub({ if (calls++ == 0) error("boom") else line(Reach.Up, Reach.Up) }, clock, backgroundScope)
            val inbox = Channel<Letter>(Channel.UNLIMITED)
            hub.subscribe(inbox)
            hub.request()
            testScheduler.runCurrent()
            backgroundScope.isActive shouldBe true
            hub.request()
            inbox.receive().shouldBeInstanceOf<Letter.LineObserved>()
            calls shouldBe 2
        }
    }
})
