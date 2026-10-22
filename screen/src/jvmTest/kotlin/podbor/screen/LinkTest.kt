package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import podbor.core.Button
import podbor.core.Stopwatch
import podbor.core.HARDCODED_SERVICES
import podbor.core.board

/** Связь экрана с VPN: что показать в ярусе «текущий VPN», пока экрана ядра нет. */
class LinkTest : FunSpec({
    val up = SessionView.Up
    val failed = SessionView.Failed("runXray")
    val fresh = LinkFacts()

    test("ничего не запускали и сервиса нет — VPN выключен (сервис — единственный держатель VPN)") {
        linkOf(fresh) shouldBe Link.Off
    }

    test("свой старт выдан, Up/Failed ещё не видели — «поднимаю VPN», даже без привязки") {
        linkOf(fresh.issued()) shouldBe Link.Starting
        linkOf(fresh.issued().bound().seen(SessionView.Starting)) shouldBe Link.Starting
        // Устаревший Down до подъёма не гасит «поднимаю»: старт ещё не дождался итога.
        linkOf(fresh.issued().bound().seen(SessionView.Down)) shouldBe Link.Starting
    }

    test("стоп посреди подъёма (Starting → Down) кончает свой старт: сервис ушёл — Off, не вечное «поднимаю»") {
        linkOf(fresh.issued().bound().seen(SessionView.Starting).seen(SessionView.Down).unbound()) shouldBe Link.Off
        linkOf(fresh.issued().bound().seen(SessionView.Starting).seen(SessionView.Down)) shouldBe Link.Off
        // Пока подъём идёт — «поднимаю».
        linkOf(fresh.issued().bound().seen(SessionView.Starting)) shouldBe Link.Starting
    }

    test("старт дождался итога: Up — экран ядра, Failed — «не поднялся»") {
        linkOf(fresh.issued().bound().seen(up)) shouldBe Link.Up
        linkOf(fresh.issued().bound().seen(failed)) shouldBe Link.Failed
    }

    test("неудачный подъём остаётся «не поднялся» и после ухода сервиса") {
        linkOf(fresh.issued().bound().seen(failed).unbound()) shouldBe Link.Failed
    }

    test("«повторить» после «не поднялся»: новый старт — «поднимаю», дальше итог нового подъёма") {
        val gone = fresh.issued().bound().seen(failed).unbound()
        linkOf(gone.issued()) shouldBe Link.Starting
        linkOf(gone.issued().bound().seen(SessionView.Starting).seen(up)) shouldBe Link.Up
        // Сервис не ушёл после неудачи: его прежний Failed уже виден, новый старт ждёт Starting.
        val stayed = fresh.issued().bound().seen(failed)
        linkOf(stayed.issued()) shouldBe Link.Starting
        linkOf(stayed.issued().seen(SessionView.Starting).seen(failed)) shouldBe Link.Failed
    }

    test("отзыв, затем возврат: сервис ушёл, своего старта нет — Off; потом согласие → Starting → Up") {
        val revoked = fresh.issued().bound().seen(up).seen(SessionView.Down).unbound()
        linkOf(revoked) shouldBe Link.Off
        val asking = revoked.askingConsent()
        linkOf(asking) shouldBe Link.Consent
        val starting = asking.issued()
        linkOf(starting) shouldBe Link.Starting
        linkOf(starting.bound().seen(SessionView.Starting)) shouldBe Link.Starting
        linkOf(starting.bound().seen(SessionView.Starting).seen(up)) shouldBe Link.Up
    }

    test("возврат к уже поднятому VPN: старт не меняет сессию (нового Up не будет) — сразу Up, не вечное «поднимаю»") {
        linkOf(fresh.bound().seen(up).issued()) shouldBe Link.Up
    }

    test("согласие не дано — «VPN не разрешён», пока не выдан новый старт") {
        val denied = fresh.askingConsent().refused()
        linkOf(denied) shouldBe Link.Refused
        linkOf(denied.issued()) shouldBe Link.Starting
    }

    test("поднятая сессия без своего старта (сервис уже работал) — по сессии; Down — Off") {
        linkOf(fresh.bound().seen(up)) shouldBe Link.Up
        linkOf(fresh.bound().seen(up).seen(SessionView.Down)) shouldBe Link.Off
        linkOf(fresh.bound().seen(SessionView.Starting)) shouldBe Link.Starting
    }

    test("сервис ушёл (отвязка после стопа) — последнее состояние остаётся, не «поднимаю VPN»") {
        val service = MutableStateFlow<String?>("a")
        val seen = mutableListOf<String>()
        runBlocking {
            val job = launch(Dispatchers.Unconfined) {
                service.keepLast { flowOf("$it-down") }.collect { seen += it }
            }
            service.value = null
            service.value = "b"
            job.cancel()
        }
        seen shouldBe listOf("a-down", "b-down")
    }
    test("лицо кнопки: VPN поднят — подбор по кнопке ядра; выключен — «включить»; прочее — ждёт") {
        val ready = board(HARDCODED_SERVICES, emptyMap()).copy(button = Button.Ready)
        faceOf(ready, Link.Up) shouldBe Face.Press(Button.Ready)
        faceOf(ready, Link.Off) shouldBe Face.TurnOn
        for (l in listOf(Link.Starting, Link.Failed, Link.Refused, Link.Consent)) faceOf(ready, l) shouldBe Face.Waiting
    }
    test("лицо кнопки несёт общий секундомер экрана") {
        val going = board(HARDCODED_SERVICES, emptyMap()).copy(button = Button.Searching, stopwatch = Stopwatch.Running(5))
        faceOf(going, Link.Up) shouldBe Face.Press(Button.Searching, Stopwatch.Running(5))
    }
})
