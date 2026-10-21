package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.ui.flagOf
import podbor.app.ui.skipProgress
import podbor.core.ScanBoard

/** Слова экрана анализа без Compose: сколько проверено в шторке пропуска и флаг страны выхода. */
class ScanWordsTest : FunSpec({
    test("шторка пропуска говорит, сколько проверено; доски ещё нет — подписка читается") {
        skipProgress(null) shouldBe "Подписка ещё читается."
        skipProgress(ScanBoard(total = 2, done = 1, ruEntries = 0, likelyWhite = 0, tallies = emptyList(), cards = emptyList(), verdict = emptyList(), finished = false)) shouldBe
            "Проверено 1 из 2."
    }

    test("флаг из кода страны; регистр не важен; не код — пусто") {
        flagOf("NL") shouldBe "🇳🇱"
        flagOf("nl") shouldBe flagOf("NL")
        flagOf("?") shouldBe ""
    }
})
