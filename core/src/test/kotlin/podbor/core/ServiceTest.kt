package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServiceTest : FunSpec({

    test("Telegram: признаки и проба") {
        Telegram.id shouldBe ServiceId("tg")
        Telegram.label shouldBe "TG"
        Telegram.title shouldBe "Телега"
        Telegram.match shouldBe Match(setOf("telegram"), setOf("telegram"), setOf("org.telegram.messenger"))
        Telegram.probe shouldBe ProbeSpec.Telegram
    }

    test("Instagram: признаки и проба") {
        Instagram.id shouldBe ServiceId("insta")
        Instagram.label shouldBe "IG"
        Instagram.title shouldBe "Инста"
        Instagram.match.domains shouldBe setOf("instagram", "facebook")
        Instagram.match.ips shouldBe setOf("facebook")
        Instagram.match.apps shouldBe setOf("com.instagram.android")
        Instagram.probe shouldBe ProbeSpec.Instagram
    }

    test("зашитый набор и сервис, за которым идёт общая сеть") {
        HARDCODED_SERVICES shouldBe setOf(Telegram, Instagram)
        GENERAL_FOLLOWS shouldBe Telegram.id
    }

    test("построитель: повтор в domains — множество, повтор ничего не меняет") {
        val s = service(ServiceId("x"), "X", "Икс", probe = ProbeSpec.Telegram) {
            match { domains("a"); domains("a", "b") }
        }
        s.match.domains shouldBe setOf("a", "b")
    }

    test("без match {} — пустые множества") {
        val s = service(ServiceId("x"), "X", "Икс", probe = ProbeSpec.Instagram)
        s.match shouldBe Match(emptySet(), emptySet(), emptySet())
    }

    test("проба — та, что передана: сервиса без пробы не построить (не компилируется)") {
        service(ServiceId("x"), "X", "Икс", probe = ProbeSpec.Instagram).probe shouldBe ProbeSpec.Instagram
    }
})
