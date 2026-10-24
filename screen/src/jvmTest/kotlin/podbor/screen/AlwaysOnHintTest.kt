package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import podbor.core.Board
import podbor.core.Button
import podbor.core.NetworkCard
import podbor.core.OsNetwork

/** Подсказка системного Always-on: раз, в момент, когда VPN впервые заработал, и только если он не включён. */
class AlwaysOnHintTest : FunSpec({

    data class Case(val name: String, val alwaysOn: AlwaysOn, val session: SessionView, val button: Button, val ask: Ask?, val due: Boolean)

    listOf(
        Case("заработал, Always-on выключен — пора", AlwaysOn.Off, SessionView.Up, Button.Green, null, true),
        Case("Android до 10 не говорит, включён ли, — подсказываем", AlwaysOn.Unknown, SessionView.Up, Button.Green, null, true),
        Case("Always-on с блокировкой уже включён — нечего подсказывать", AlwaysOn.On, SessionView.Up, Button.Green, null, false),
        Case("ещё не заработал — не мешаем подбору", AlwaysOn.Off, SessionView.Up, Button.Searching, null, false),
        Case("VPN не поднят — рано", AlwaysOn.Off, SessionView.Starting, Button.Green, null, false),
        Case("открыта другая шторка — ждём своей очереди", AlwaysOn.Off, SessionView.Up, Button.Green, Ask.Disclosure, false),
    ).forEach { c ->
        test("пора ли подсказать: ${c.name}") { alwaysOnHintDue(c.alwaysOn, c.session, c.button, c.ask) shouldBe c.due }
    }

    val green = Board(NetworkCard.Measuring(OsNetwork.Wifi), Button.Green, emptyList())

    test("экран: не показывали — шторка, как только VPN заработал") {
        vmTest {
            val vm = vm()
            val port = FakePort().apply { always = AlwaysOn.Off }
            vm.attach(port)
            vm.hintAlwaysOn(seen = false)
            runCurrent()
            vm.ask.value shouldBe null
            port.session.value = SessionView.Up
            port.board.value = green
            runCurrent()
            vm.ask.value shouldBe Ask.AlwaysOn
        }
    }

    test("экран: уже показывали — молчит; ответ закрывает шторку и второй раз она не приходит") {
        vmTest {
            val seen = vm()
            val port = FakePort().apply { always = AlwaysOn.Off }
            seen.attach(port)
            seen.hintAlwaysOn(seen = true)
            port.session.value = SessionView.Up
            port.board.value = green
            runCurrent()
            seen.ask.value shouldBe null

            val fresh = vm()
            fresh.attach(port)
            fresh.hintAlwaysOn(seen = false)
            runCurrent()
            fresh.ask.value shouldBe Ask.AlwaysOn
            fresh.answered()
            fresh.detach()
            fresh.attach(port)
            runCurrent()
            fresh.ask.value shouldBe null
        }
    }
})
