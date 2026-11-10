package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent

private class FakePlatform(private val needsConsent: Boolean) : SessionPlatform {
    val done = mutableListOf<String>()
    override fun consentNeeded() = needsConsent
    override fun askConsent() { done += "ask" }
    override fun startEngine() { done += "start" }
    override fun followEngine() { done += "follow" }
    override fun stopEngine() { done += "stop" }
    override fun reopen() { done += "reopen" }
}

/** История включения VPN — одна на все ОС: автомат и его действия на платформе с согласием и без него. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionStoryTest : FunSpec({
    val raise = listOf(SessionDeed.StartEngine, SessionDeed.MarkIssued, SessionDeed.FollowEngine)

    test("автомат: пора стартовать — сперва узнать про согласие, разрешено — подъём по порядку") {
        sessionDeeds(false, SessionLetter.StartDue) shouldBe listOf(SessionDeed.CheckConsent)
        sessionDeeds(false, SessionLetter.Retry) shouldBe listOf(SessionDeed.CheckConsent)
        sessionDeeds(false, SessionLetter.ConsentClear) shouldBe raise
    }

    test("автомат: пока окно согласия открыто, второго подъёма нет") {
        sessionDeeds(true, SessionLetter.StartDue).shouldBeEmpty()
        sessionDeeds(true, SessionLetter.Retry).shouldBeEmpty()
    }

    test("автомат: ответ на согласие: да — подъём, нет — только отказ") {
        sessionDeeds(true, SessionLetter.ConsentAnswered(true)) shouldBe listOf(SessionDeed.MarkAnswered) + raise
        sessionDeeds(true, SessionLetter.ConsentAnswered(false)) shouldBe listOf(SessionDeed.MarkAnswered, SessionDeed.MarkRefused)
    }

    test("ОС без согласия: старт сразу, один раз, и экран знает, что выдан") {
        vmTest {
            val vm = vm()
            val os = FakePlatform(needsConsent = false)
            SessionStory(vm, os).send(SessionLetter.StartDue)
            runCurrent()
            os.done shouldBe listOf("start", "follow")
            linkOf(vm.facts.value) shouldBe Link.Starting
        }
    }

    test("ОС с согласием: спросить, не стартуя; повтор не открывает второе окно; да — подъём") {
        vmTest {
            val vm = vm()
            val os = FakePlatform(needsConsent = true)
            val story = SessionStory(vm, os)
            story.send(SessionLetter.StartDue)
            runCurrent()
            os.done shouldBe listOf("ask")
            vm.ask.value shouldBe Ask.VpnConsent
            story.send(SessionLetter.Retry)
            os.done shouldBe listOf("ask")
            story.send(SessionLetter.ConsentAnswered(true))
            runCurrent()
            os.done shouldBe listOf("ask", "start", "follow")
            vm.ask.value shouldBe null
        }
    }

    test("отказ в согласии — движок не трогаем, экран говорит об отказе") {
        vmTest {
            val vm = vm()
            val os = FakePlatform(needsConsent = true)
            val story = SessionStory(vm, os)
            story.send(SessionLetter.StartDue)
            story.send(SessionLetter.ConsentAnswered(false))
            runCurrent()
            os.done shouldBe listOf("ask")
            linkOf(vm.facts.value) shouldBe Link.Refused
        }
    }

    test("выключить и включить — действия платформы") {
        vmTest {
            val os = FakePlatform(needsConsent = false)
            val story = SessionStory(vm(), os)
            story.send(SessionLetter.TurnOff)
            story.send(SessionLetter.TurnOn)
            os.done shouldBe listOf("stop", "reopen")
        }
    }
})
