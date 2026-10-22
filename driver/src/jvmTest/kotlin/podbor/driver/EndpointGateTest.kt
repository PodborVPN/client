@file:OptIn(ExperimentalCoroutinesApi::class)

package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import podbor.core.Endpoint

class EndpointGateTest : FunSpec({
    val a = Endpoint("1.1.1.1", 443)
    val b = Endpoint("198.51.100.2", 443)

    test("на одном адресе второй начинается после конца первого") {
        runTest {
            val gate = EndpointGate()
            val starts = mutableListOf<Long>()
            List(2) { async { gate.withEndpoint(a) { starts += currentTime; delay(100) } } }.awaitAll()
            starts shouldBe listOf(0L, 100L)
        }
    }

    test("на разных адресах — параллельно") {
        runTest {
            val gate = EndpointGate()
            val starts = mutableListOf<Long>()
            listOf(a, b).map { e -> async { gate.withEndpoint(e) { starts += currentTime; delay(100) } } }.awaitAll()
            starts shouldBe listOf(0L, 0L)
            currentTime shouldBe 100L
        }
    }

    test("perEndpoint = 2 пускает двоих разом") {
        runTest {
            val gate = EndpointGate(2)
            val starts = mutableListOf<Long>()
            List(3) { async { gate.withEndpoint(a) { starts += currentTime; delay(100) } } }.awaitAll()
            starts shouldBe listOf(0L, 0L, 100L)
        }
    }

    test("ожидающие с приоритетом проходят раньше ожидающих без него, внутри класса — по приходу") {
        runTest {
            val gate = EndpointGate()
            val order = mutableListOf<String>()
            val holder = async { gate.withEndpoint(a) { delay(100) } }
            delay(1)
            val plain1 = async { gate.withEndpoint(a) { order += "plain1"; delay(10) } }
            delay(1)
            val plain2 = async { gate.withEndpoint(a) { order += "plain2"; delay(10) } }
            delay(1)
            val first = async { gate.withEndpoint(a, priority = true) { order += "first"; delay(10) } }
            delay(1)
            val second = async { gate.withEndpoint(a, priority = true) { order += "second"; delay(10) } }
            listOf(holder, plain1, plain2, first, second).awaitAll()
            order shouldBe listOf("first", "second", "plain1", "plain2")
        }
    }

    test("отменённый ожидающий не уносит место: следующий проходит") {
        runTest {
            val gate = EndpointGate()
            val holder = async { gate.withEndpoint(a) { delay(100) } }
            delay(1)
            val cancelled = async { gate.withEndpoint(a, priority = true) { error("не должен начаться") } }
            delay(1)
            var ran = false
            val next = async { gate.withEndpoint(a) { ran = true } }
            delay(1)
            cancelled.cancel()
            holder.await(); next.await()
            ran shouldBe true
            currentTime shouldBe 100L
        }
    }
})
