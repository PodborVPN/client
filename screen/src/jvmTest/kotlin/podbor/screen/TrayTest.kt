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
import podbor.core.NetworkMove
import podbor.core.OsNetwork
import podbor.core.ServiceCard
import podbor.core.Telegram
import podbor.core.Transport

/** Шторка — плитка и уведомление: проекции одного состояния [Status], говорят одно и то же. */
class TrayTest : FunSpec({

    val nl = ExitRef("NL-7", Transport.Grpc)

    fun board(button: Button, ig: CardStatus, tg: CardStatus) = Board(
        NetworkCard.Measuring(OsNetwork.Wifi), button,
        listOf(ServiceCard(Instagram.id, "IG", ig), ServiceCard(Telegram.id, "TG", tg)),
    )

    val idle = board(Button.Idle, CardStatus.Unknown, CardStatus.Unknown)
    val works = board(Button.Green, CardStatus.Works(nl), CardStatus.Works(nl))
    val move = NetworkMove(OsNetwork.Wifi, OsNetwork.Cellular("MTS", "25001"))
    fun status(session: SessionView, b: Board = idle, work: Work = Work.Idle) = Status(session, b, work)

    test("сессия не поднята — выключен; поднимается — включаю; не поднялась — открой Podbor") {
        trayOf(status(SessionView.Down)) shouldBe Tray(on = false, line = "выключен")
        trayOf(status(SessionView.Starting)) shouldBe Tray(on = true, line = "включаю…")
        trayOf(status(SessionView.Failed("tun"))) shouldBe Tray(on = false, line = "не включился — открой Podbor")
    }

    test("подбор идёт — подбираю, что бы ни было на карточках") {
        trayOf(status(SessionView.Up, board(Button.Searching, CardStatus.Searching, CardStatus.Works(nl)))) shouldBe
            Tray(on = true, line = "подбираю…")
    }

    test("идёт анализ — плитка говорит это, а не «работает»: VPN на время анализа выключен") {
        trayOf(status(SessionView.Down, work = Work.Analysis(3, 79))) shouldBe Tray(on = false, line = "анализирую подписку · 3 из 79")
        trayOf(status(SessionView.Up, works, Work.Analysis(0, 79))) shouldBe Tray(on = false, line = "анализирую подписку · 0 из 79")
    }

    test("перемер после переезда — его строка и в плитке, и в уведомлении") {
        trayOf(status(SessionView.Up, works, Work.Remeasure(move))) shouldBe
            Tray(on = true, line = "сеть сменилась: Wi-Fi → MTS — проверяю выходы")
        shadeOf(status(SessionView.Up, works, Work.Remeasure(move))) shouldBe
            Shade.Shown("сеть сменилась: Wi-Fi → MTS — проверяю выходы", listOf(ShadeButton.Press, ShadeButton.Stop))
    }

    test("тап по плитке: включён — выключить; выключен — включить, а без согласия на VPN — открыть Podbor (спросит он)") {
        tapOf(Tray(on = true, line = "IG ✓ · TG ✓"), consented = true) shouldBe Tap.Stop
        tapOf(Tray(on = true, line = "включаю…"), consented = false) shouldBe Tap.Stop
        tapOf(Tray(on = false, line = "выключен"), consented = true) shouldBe Tap.Start
        tapOf(Tray(on = false, line = "выключен"), consented = false) shouldBe Tap.OpenApp
    }

    test("уведомление: нет сессии — снять (а не оставить прежнее); поднимается — только «Выключить»") {
        shadeOf(status(SessionView.Down)) shouldBe Shade.Hidden
        shadeOf(status(SessionView.Failed("tun"))) shouldBe Shade.Hidden
        shadeOf(status(SessionView.Starting)) shouldBe Shade.Shown("включаю…", listOf(ShadeButton.Stop))
    }

    test("уведомление во время анализа — снять: VPN выключен, «работает» было бы неправдой") {
        shadeOf(status(SessionView.Down, works, Work.Analysis(3, 79))) shouldBe Shade.Hidden
        shadeOf(status(SessionView.Up, works, Work.Analysis(0, 79))) shouldBe Shade.Hidden
    }

    test("уведомление на поднятой: статус шторки и обе кнопки") {
        shadeOf(status(SessionView.Up, works)) shouldBe Shade.Shown("IG ✓ · TG ✓", listOf(ShadeButton.Press, ShadeButton.Stop))
    }

    test("поднята — по сервису: работает ✓, мёртв или не найден ✗, проверяю …, неизвестно —") {
        trayOf(status(SessionView.Up, board(Button.Green, CardStatus.Works(nl), CardStatus.Found(nl, 0, "проверено 1 из 3")))) shouldBe
            Tray(on = true, line = "IG ✓ · TG ✓")
        trayOf(status(SessionView.Up, board(Button.Ready, CardStatus.Dead(nl, Blame.Server), CardStatus.Checking(nl)))) shouldBe
            Tray(on = true, line = "IG ✗ · TG …")
        trayOf(status(SessionView.Up, board(Button.Red, CardStatus.NotFound(listOf("Нет сети.")), CardStatus.Unknown))) shouldBe
            Tray(on = true, line = "IG ✗ · TG —")
    }
})
