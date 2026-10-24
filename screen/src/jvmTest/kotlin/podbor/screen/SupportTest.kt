package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.Button

/** Поддержка провайдера на главном — только когда ничего не работает и провайдер дал ссылку. */
class SupportTest : FunSpec({

    val help = Support.Link("https://t.me/help")

    test("подбор кончился ничем (кнопка красная) и ссылка есть — кнопка поддержки") {
        supportWhenStuck(Button.Red, help) shouldBe help
    }

    test("ссылки нет — кнопки нет, что бы ни было") {
        Button.entries.forEach { supportWhenStuck(it, Support.None) shouldBe Support.None }
    }

    test("хоть что-то работает, ищем или ещё не нажимали — не отвлекаем поддержкой") {
        listOf(Button.Idle, Button.Ready, Button.Searching, Button.Green).forEach { supportWhenStuck(it, help) shouldBe Support.None }
    }
})
