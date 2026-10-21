@file:OptIn(ExperimentalCoroutinesApi::class)

package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/** Доска в UI не чаще раза в кадр: промежуточные пропускаются, последняя — всегда. */
class PacedTest : FunSpec({

    test("частые значения: отдаются не чаще раза в период, последнее — всегда; итог блока — как есть") {
        runTest {
            val got = mutableListOf<Pair<Long, Int>>()
            val out = paced(16, { v: Int -> got += currentTime to v }) { emit ->
                for (i in 1..100) { emit(i); delay(1) }
                "итог"
            }
            out shouldBe "итог"
            got.last().second shouldBe 100
            got.size shouldBeLessThanOrEqual 100 / 16 + 2
            // Между отдачами — не меньше периода (кроме последней: досылается по концу блока сразу).
            got.dropLast(1).zipWithNext { a, b -> b.first - a.first }.forEach { it shouldBeGreaterThanOrEqual 16L }
            got.map { it.second } shouldBe got.map { it.second }.sorted()
        }
    }

    test("одно значение — отдаётся; ни одного — ничего") {
        runTest {
            val one = mutableListOf<Int>()
            paced(16, { v: Int -> one += v }) { emit -> emit(7) }
            one shouldBe listOf(7)
            val none = mutableListOf<Int>()
            paced(16, { v: Int -> none += v }) { _ -> Unit }
            none.shouldBeEmpty()
        }
    }
})
