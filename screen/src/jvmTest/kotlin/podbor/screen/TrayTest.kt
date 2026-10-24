package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.Blame
import podbor.core.Board
import podbor.core.Button
import podbor.core.CardStatus
import podbor.core.ExitRef
import podbor.core.Instagram
import podbor.core.NetworkCard
import podbor.core.OsNetwork
import podbor.core.ServiceCard
import podbor.core.Telegram
import podbor.core.Transport

/** Статус для шторки — плитка и уведомление говорят одно и то же. */
class TrayTest : FunSpec({

    val nl = ExitRef("NL-7", Transport.Grpc)

    fun board(button: Button, ig: CardStatus, tg: CardStatus) = Board(
        NetworkCard.Measuring(OsNetwork.Wifi), button,
        listOf(ServiceCard(Instagram.id, "IG", ig), ServiceCard(Telegram.id, "TG", tg)),
    )

    val idle = board(Button.Idle, CardStatus.Unknown, CardStatus.Unknown)

    test("сессия не поднята — выключен; поднимается — включаю; не поднялась — открой Podbor") {
        trayOf(SessionView.Down, idle) shouldBe Tray(on = false, line = "выключен")
        trayOf(SessionView.Starting, idle) shouldBe Tray(on = true, line = "включаю…")
        trayOf(SessionView.Failed("tun"), idle) shouldBe Tray(on = false, line = "не включился — открой Podbor")
    }

    test("подбор идёт — подбираю, что бы ни было на карточках") {
        trayOf(SessionView.Up, board(Button.Searching, CardStatus.Searching, CardStatus.Works(nl))) shouldBe
            Tray(on = true, line = "подбираю…")
    }

    test("тап по плитке: включён — выключить; выключен — включить, а без согласия на VPN — открыть Podbor (спросит он)") {
        tapOf(Tray(on = true, line = "IG ✓ · TG ✓"), consented = true) shouldBe Tap.Stop
        tapOf(Tray(on = true, line = "включаю…"), consented = false) shouldBe Tap.Stop
        tapOf(Tray(on = false, line = "выключен"), consented = true) shouldBe Tap.Start
        tapOf(Tray(on = false, line = "выключен"), consented = false) shouldBe Tap.OpenApp
    }

    test("поднята — по сервису: работает ✓, мёртв или не найден ✗, проверяю …, неизвестно —") {
        trayOf(SessionView.Up, board(Button.Green, CardStatus.Works(nl), CardStatus.Found(nl, 0, "проверено 1 из 3"))) shouldBe
            Tray(on = true, line = "IG ✓ · TG ✓")
        trayOf(SessionView.Up, board(Button.Ready, CardStatus.Dead(nl, Blame.Server), CardStatus.Checking(nl))) shouldBe
            Tray(on = true, line = "IG ✗ · TG …")
        trayOf(SessionView.Up, board(Button.Red, CardStatus.NotFound(listOf("Нет сети.")), CardStatus.Unknown)) shouldBe
            Tray(on = true, line = "IG ✗ · TG —")
    }
})
