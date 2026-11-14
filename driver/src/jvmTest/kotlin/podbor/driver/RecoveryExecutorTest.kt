package podbor.driver

import arrow.core.left
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.RecoveryChange
import podbor.core.RecoveryDeed
import podbor.core.RecoveryLetter
import podbor.core.RecoveryLine
import podbor.core.RecoveryRoute

class RecoveryExecutorTest : FunSpec({
    test("ошибка вызова проверки линии возвращается отдельно от её результата") {
        val failure = EngineError.Failed("line")
        val ports = object : RecoveryPorts {
            override suspend fun checkLine(attempt: Long) = failure.left()
            override suspend fun find(attempt: Long, routes: Map<RecoveryRoute, podbor.core.ServerId>) =
                emptyMap<RecoveryRoute, podbor.core.ServerId>().right()
            override suspend fun apply(attempt: Long, changes: Map<RecoveryRoute, RecoveryChange>) =
                emptyMap<RecoveryRoute, podbor.core.RecoveryApplied>().right()
            override suspend fun cancel(attempt: Long) = Unit
        }
        val errors = mutableListOf<EngineError>()
        val letters = mutableListOf<RecoveryLetter>()
        executeRecovery(RecoveryDeed.CheckLine(3), ports, { errors += it }, { letters += it })
        errors shouldBe listOf(failure)
        letters shouldBe listOf(RecoveryLetter.LineChecked(3, RecoveryLine.Unknown))
    }
    test("опрос выходов начинается только после проверки линии в той же сети") {
        for (round in podbor.core.RoundKind.entries) {
            for (sameNetwork in listOf(true, false)) {
                val events = mutableListOf<String>()
                withRecoveryLine(
                    measure = { events += "линия"; round },
                    currentNetwork = { sameNetwork },
                    report = { events += "ошибка" },
                    proceed = { events += "наблюдение" },
                )
                val reachable = round in setOf(podbor.core.RoundKind.Open, podbor.core.RoundKind.WhitelistLike)
                events shouldBe if (reachable && sameNetwork) listOf("линия", "наблюдение") else listOf("линия")
            }
        }
    }
})
