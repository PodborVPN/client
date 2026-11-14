package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RecoveryTest : FunSpec({
    val old = ServerId("first")
    val spare = ServerId("second")
    val tg = RecoveryRoute.Service(Telegram.id)
    val ig = RecoveryRoute.Service(Instagram.id)
    val routes = mapOf(tg to old, ig to spare, RecoveryRoute.General to old)

    fun start(evidence: RecoveryEvidence) =
        recoveryStep(RecoveryState.Watching, RecoveryLetter.Observed(1, evidence, routes))

    test("сбой пары проверяет линию и ищет только для сервиса") {
        val (checking, check) = start(RecoveryEvidence.PairFailed(tg, old))
        check shouldBe listOf(RecoveryDeed.CheckLine(1))
        val (_, search) = recoveryStep(checking, RecoveryLetter.LineChecked(1, RecoveryLine.Reachable))
        search shouldBe listOf(RecoveryDeed.Find(1, mapOf(tg to old)))
    }

    test("отказ затрагивает только подтверждённые маршруты через выход") {
        val (checking, _) = start(RecoveryEvidence.ExitFailed(old))
        val (_, search) = recoveryStep(checking, RecoveryLetter.LineChecked(1, RecoveryLine.Reachable))
        search shouldBe listOf(RecoveryDeed.Find(1, mapOf(tg to old, RecoveryRoute.General to old)))
    }

    test("при неизвестном состоянии линии подбор ждёт следующего наблюдения") {
        val (checking, _) = start(RecoveryEvidence.ExitFailed(old))
        recoveryStep(checking, RecoveryLetter.LineChecked(1, RecoveryLine.Unknown)) shouldBe
            (RecoveryState.Watching to emptyList())
    }

    test("результат вытесненной попытки не меняет маршрут") {
        val (checking, _) = start(RecoveryEvidence.ExitFailed(old))
        val (searching, _) = recoveryStep(checking, RecoveryLetter.LineChecked(1, RecoveryLine.Reachable))
        val (cancelled, _) = recoveryStep(searching, RecoveryLetter.Invalidated)
        recoveryStep(cancelled, RecoveryLetter.Candidates(1, mapOf(tg to spare))) shouldBe
            (RecoveryState.Watching to emptyList())
    }

    test("применение принимает только замену затронутому маршруту") {
        val (checking, _) = start(RecoveryEvidence.PairFailed(tg, old))
        val (searching, _) = recoveryStep(checking, RecoveryLetter.LineChecked(1, RecoveryLine.Reachable))
        val (_, deeds) = recoveryStep(searching, RecoveryLetter.Candidates(1, mapOf(tg to spare, ig to old)))
        deeds shouldBe listOf(RecoveryDeed.Apply(1, mapOf(tg to RecoveryChange(old, spare))))
    }

    test("неактуальный отказ пары не будит подбор") {
        start(RecoveryEvidence.PairFailed(ig, old)) shouldBe (RecoveryState.Watching to emptyList())
    }
    test("частичное применение хранит исход каждого маршрута") {
        val (checking, _) = start(RecoveryEvidence.ExitFailed(old))
        val (searching, _) = recoveryStep(checking, RecoveryLetter.LineChecked(1, RecoveryLine.Reachable))
        val (applying, _) = recoveryStep(
            searching, RecoveryLetter.Candidates(1, mapOf(tg to spare, RecoveryRoute.General to spare)),
        )
        recoveryStep(applying, RecoveryLetter.Applied(1, mapOf(tg to RecoveryApplied.Applied))) shouldBe
            (RecoveryState.Settled(1, mapOf(tg to RecoveryApplied.Applied, RecoveryRoute.General to RecoveryApplied.Unknown)) to emptyList())
    }
})
