package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.ScanBoard

private val board = ScanBoard(3, 1, 1, 0, emptyList(), emptyList(), emptyList(), finished = false)

/** Какая страница под сетью: подписка, анализ или главный — чистая функция от фактов экрана. */
class PagesTest : FunSpec({
    test("нет подписки → «Добавь подписку»") {
        pageOf(hasSubscription = false, scan = ScanPhase.Idle) shouldBe Page.AddSubscription
    }

    test("анализ идёт или кончился, но «ОК» ещё не нажато → анализ с его доской") {
        pageOf(false, ScanPhase.Running(null)) shouldBe Page.Scanning(null)
        pageOf(true, ScanPhase.Running(board)) shouldBe Page.Scanning(board)
        pageOf(true, ScanPhase.Done(board.copy(finished = true))) shouldBe Page.Scanning(board.copy(finished = true))
    }

    test("есть подписка и анализ не идёт → главный") {
        pageOf(hasSubscription = true, scan = ScanPhase.Idle) shouldBe Page.Main
    }

    test("подписка не принялась → снова «Добавь подписку» (ошибка словами — на ней), и при сохранённой подписке") {
        pageOf(false, ScanPhase.Failed(InputError.NoServers(MIXED.copy(servers = 0)))) shouldBe Page.AddSubscription
        pageOf(true, ScanPhase.Failed(InputError.Download(false))) shouldBe Page.AddSubscription
    }

    test("«Сменить подписку» на главном → «Добавь подписку», хотя подписка есть") {
        pageOf(true, ScanPhase.Choosing) shouldBe Page.AddSubscription
    }
})
