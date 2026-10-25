package podbor.screen

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.ScanBoard

private val board = ScanBoard(3, 1, 1, 0, emptyList(), emptyList(), emptyList(), finished = false)
private val chosen = Subscriptions.Chosen(CARD, emptyList())

/** Какая страница под сетью: подписка, анализ или главный — чистая функция от фактов экрана. */
class PagesTest : FunSpec({
    test("нет подписки → «Добавь подписку»") {
        pageOf(Subscriptions.None, ScanPhase.Idle) shouldBe Page.AddSubscription
    }

    test("подписки есть, а текущей нет (удалили) → страница подписки, не главный") {
        pageOf(Subscriptions.Unchosen(nonEmptyListOf(CARD)), ScanPhase.Idle) shouldBe Page.AddSubscription
    }

    test("анализ идёт или кончился, но «ОК» ещё не нажато → анализ с его доской") {
        pageOf(Subscriptions.None, ScanPhase.Running(null)) shouldBe Page.Scanning(null)
        pageOf(chosen, ScanPhase.Running(board)) shouldBe Page.Scanning(board)
        pageOf(chosen, ScanPhase.Done(board.copy(finished = true))) shouldBe Page.Scanning(board.copy(finished = true))
    }

    test("текущая есть и анализ не идёт → главный") {
        pageOf(chosen, ScanPhase.Idle) shouldBe Page.Main
    }

    test("подписка не принялась → снова «Добавь подписку» (ошибка словами — на ней), и при сохранённой подписке") {
        pageOf(Subscriptions.None, ScanPhase.Failed(InputError.NoServers(MIXED.copy(servers = 0)))) shouldBe Page.AddSubscription
        pageOf(chosen, ScanPhase.Failed(InputError.Download(false))) shouldBe Page.AddSubscription
    }

    test("«Сменить подписку» на главном → «Добавь подписку», хотя подписка есть") {
        pageOf(chosen, ScanPhase.Choosing) shouldBe Page.AddSubscription
    }

    test("cards — текущая первой; isCurrent — только у выбранной") {
        val other = CARD.copy(id = "b")
        val subs = Subscriptions.Chosen(CARD, listOf(other))
        subs.cards() shouldBe listOf(CARD, other)
        subs.isCurrent(CARD.id) shouldBe true
        subs.isCurrent("b") shouldBe false
        Subscriptions.Unchosen(nonEmptyListOf(CARD)).isCurrent(CARD.id) shouldBe false
    }
})
