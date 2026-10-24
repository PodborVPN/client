package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
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
import podbor.core.PairPulse
import podbor.core.Since
import podbor.core.PulseView
import podbor.core.Relocation
import podbor.core.Scenario
import podbor.core.Tally
import podbor.core.Traffic
import podbor.core.ServerId
import podbor.core.ServiceCard
import podbor.core.ServiceId
import podbor.core.Stopwatch
import podbor.core.Telegram
import podbor.core.Transport

/** Вид экрана без Compose: цвет и щит по состоянию, строки сервисов в блоке «текущий VPN». */
class LookTest : FunSpec({
    val mts = OsNetwork.Cellular("MTS", "25001")
    fun measured(s: Scenario) = NetworkCard.Measured(mts, s, "")
    val nl = ExitRef("🇳🇱 NL-7", Transport.Grpc)
    val nlx = ExitRef("🇳🇱 Netherlands-60s(xHTTP)", Transport.Xhttp)

    /** Экран из карточек в порядке Телега, Инста — строки всё равно по ServiceId. */
    fun board(button: Button, tg: CardStatus, ig: CardStatus = CardStatus.Unknown) =
        Board(NetworkCard.Measuring(mts), button, listOf(ServiceCard(Telegram.id, "TG", tg), ServiceCard(Instagram.id, "IG", ig)))

    /** Строка Телеги на поднятом VPN. */
    fun tg(status: CardStatus, button: Button = Button.Ready, origin: Map<ServiceId, ExitOrigin> = emptyMap()) =
        rowsOf(board(button, status), Link.Up, origin).single { it.label == "TG" }

    test("точка сети: замер серый, белые списки и подозрение янтарные, чистая и Wi-Fi зелёные, нет сети красная") {
        toneOf(NetworkCard.Measuring(mts)) shouldBe Tone.Muted
        toneOf(measured(Scenario.MobileWhitelist)) shouldBe Tone.Warn
        toneOf(measured(Scenario.SuspectWhitelist)) shouldBe Tone.Warn
        toneOf(measured(Scenario.MobileOpen)) shouldBe Tone.Ok
        toneOf(measured(Scenario.Wifi)) shouldBe Tone.Ok
        toneOf(measured(Scenario.Offline)) shouldBe Tone.Fail
        toneOf(measured(Scenario.Unclear)) shouldBe Tone.Neutral
    }

    test("кнопка: цвет и щит по состоянию") {
        Button.entries.map { toneOf(it) to shieldOf(it) } shouldBe listOf(
            Tone.Muted to Shield.Plain, // Idle
            Tone.Warn to Shield.Plain, // Ready
            Tone.Warn to Shield.Plain, // Searching
            Tone.Ok to Shield.Check, // Green
            Tone.Fail to Shield.Cross, // Red
        )
    }

    test("строка на сервис по ServiceId: IG не нашёл — ✗ и «нет рабочего сервера»; TG нашёл — ✓, сервер, протокол") {
        val rows = rowsOf(
            board(Button.Green, CardStatus.Found(nlx, 0, "проверено 2 из 92 серверов"), CardStatus.NotFound(listOf("Белые списки.", "Проверил 3 сервера:"))),
            Link.Up,
            emptyMap(),
        )
        rows shouldHaveSize 2
        rows[0] shouldBe ServiceRow(
            "IG", Mark.Dead, "нет рабочего сервера", null,
            listOf(Note("Белые списки.", Tone.Neutral), Note("Проверил 3 сервера:", Tone.Muted)),
            searching = false,
            mini = Note("Белые списки.", Tone.Fail),
        )
        rows[1] shouldBe ServiceRow(
            "TG", Mark.Works, "🇳🇱 Netherlands-60s(xHTTP)", "XHTTP", listOf(Note("проверено 2 из 92 серверов", Tone.Ok)), searching = false,
            mini = Note("найден · проверено 2 из 92 серверов", Tone.Ok),
        )
    }

    test("строка: работающий выход — ✓, имя, протокол; мёртвый — ✗ и вина красным; проверка — «проверяю <сервис>…»") {
        tg(CardStatus.Works(nl)) shouldBe
            ServiceRow("TG", Mark.Works, "🇳🇱 NL-7", "gRPC", emptyList(), searching = false, mini = Note("работает", Tone.Ok))
        tg(CardStatus.Dead(nl, Blame.Server)) shouldBe ServiceRow(
            "TG", Mark.Dead, "🇳🇱 NL-7", "gRPC", listOf(Note("сеть ни при чём — проблема у сервера", Tone.Fail)), searching = false,
            mini = Note("сервер не отвечает", Tone.Fail),
        )
        tg(CardStatus.Checking(nl)) shouldBe ServiceRow(
            "TG", Mark.Checking, "🇳🇱 NL-7", "gRPC", listOf(Note("проверяю Телегу…", Tone.Warn)), searching = false,
            mini = Note("проверяю", Tone.Warn),
        )
        rowsOf(board(Button.Ready, CardStatus.Unknown, CardStatus.Checking(nl)), Link.Up, emptyMap())
            .single { it.label == "IG" }.notes shouldBe listOf(Note("проверяю Инсту…", Tone.Warn))
    }

    test("строка: выход не выбран — честные слова ядра, без протокола и отметки; не из подписки — тег") {
        tg(CardStatus.Unknown) shouldBe ServiceRow(
            "TG", null, "выход не выбран", null, listOf(Note("Xray выбирает сам — нажми «Подобрать»", Tone.Muted)), searching = false,
            mini = Note("Xray выбирает сам", Tone.Muted),
        )
        tg(CardStatus.Unlisted(ServerId("s042"))) shouldBe ServiceRow(
            "TG", null, "s042", null, listOf(Note("такого сервера нет в подписке, проверить нечем", Tone.Muted)), searching = false,
            mini = Note("нет в подписке", Tone.Muted),
        )
    }

    test("строка: поиск — полоска только во время поиска, у каждого сервиса своя") {
        val rows = rowsOf(board(Button.Searching, CardStatus.Searching, CardStatus.Found(nl, 0, "проверено 1 из 9")), Link.Up, emptyMap())
        rows.single { it.label == "TG" } shouldBe
            ServiceRow("TG", null, "подбираю выход…", null, emptyList(), searching = true, mini = Note("подбираю…", Tone.Warn))
        rows.single { it.label == "IG" }.searching shouldBe false
    }

    test("без поднятого VPN — одна строка его состояния без сервиса, а не устаревший результат") {
        val stale = board(Button.Green, CardStatus.Works(nl), CardStatus.Works(nl))
        fun one(link: Link) = rowsOf(stale, link, emptyMap()).single()
        one(Link.Off).name shouldBe "VPN выключен"
        one(Link.Off).notes.map { it.text } shouldBe listOf("нажми «Включить»")
        one(Link.Failed).name shouldBe "VPN не поднялся"
        one(Link.Starting).name shouldBe "поднимаю VPN…"
        one(Link.Consent).name shouldBe "жду разрешения на VPN…"
        one(Link.Refused).name shouldBe "VPN не разрешён"
        Link.entries.filter { it != Link.Up }.forEach { one(it).mark shouldBe null; one(it).label shouldBe "" }
    }

    test("VPN не поднялся — одна строка с «повторить», а не «закрой Podbor и открой ещё раз»; в остальных состояниях повтора нет") {
        val stale = board(Button.Green, CardStatus.Works(nl))
        val failed = rowsOf(stale, Link.Failed, emptyMap()).single()
        failed.retry shouldBe true
        failed.notes.none { "закрой" in it.text } shouldBe true
        Link.entries.filter { it != Link.Failed }.forEach { link -> rowsOf(stale, link, emptyMap()).none { it.retry } shouldBe true }
    }

    test("восстановленный из памяти выход на проверке — «последний рабочий: <имя> — проверяю», по сервису; итог — как всегда") {
        val recalledTg = mapOf(Telegram.id to ExitOrigin.Recalled)
        tg(CardStatus.Checking(nl), origin = recalledTg) shouldBe ServiceRow(
            "TG", Mark.Checking, "🇳🇱 NL-7", "gRPC", listOf(Note("последний рабочий: 🇳🇱 NL-7 — проверяю", Tone.Warn)), searching = false,
            mini = Note("последний рабочий — проверяю", Tone.Warn),
        )
        // Восстановлен только выход Телеги: Инста проверяет общий — своими словами.
        rowsOf(board(Button.Ready, CardStatus.Checking(nl), CardStatus.Checking(nl)), Link.Up, recalledTg)
            .single { it.label == "IG" }.notes shouldBe listOf(Note("проверяю Инсту…", Tone.Warn))
        tg(CardStatus.Works(nl), origin = recalledTg) shouldBe tg(CardStatus.Works(nl))
        tg(CardStatus.Dead(nl, Blame.Server), origin = recalledTg).notes shouldBe listOf(Note("сеть ни при чём — проблема у сервера", Tone.Fail))
    }

    test("выход подобран на другой сети — строка говорит о переезде: проверяю, жив (зелёным), мёртв (причина и подсказка)") {
        val moved = Relocation.Moved(NetworkMove(OsNetwork.Wifi, mts))
        tg(CardStatus.Checking(nl, moved)).notes shouldBe listOf(Note("сеть сменилась: Wi-Fi → ${mts.operator} — проверяю Телегу…", Tone.Warn))
        tg(CardStatus.Works(nl, moved)).notes shouldBe listOf(Note("подобран на Wi-Fi, на ${mts.operator} тоже работает", Tone.Ok))
        tg(CardStatus.Dead(nl, Blame.Server, moved)).notes shouldBe listOf(
            Note("сеть ни при чём — проблема у сервера", Tone.Fail),
            Note("подобран на Wi-Fi — нажми, подберу под ${mts.operator}", Tone.Warn),
        )
        tg(CardStatus.Works(nl, Relocation.Stayed)).notes shouldBe emptyList()
    }

    test("не нашли оба — общая строка сети один раз, у первого; свои строки — у каждого") {
        val rows = rowsOf(
            board(Button.Red, CardStatus.NotFound(listOf("Белые списки.", "TG: проверил 3")), CardStatus.NotFound(listOf("Белые списки.", "IG: проверил 5"))),
            Link.Up,
            emptyMap(),
        )
        rows.map { r -> r.notes.map { it.text } } shouldBe listOf(listOf("Белые списки.", "IG: проверил 5"), listOf("TG: проверил 3"))
        rows[1].notes shouldBe listOf(Note("TG: проверил 3", Tone.Muted))
    }

    test("карточка сервиса без описания — строка с её меткой, без слов сервиса, не падение") {
        val stray = Board(NetworkCard.Measuring(mts), Button.Ready, listOf(ServiceCard(ServiceId("yt"), "YT", CardStatus.Checking(nl))))
        rowsOf(stray, Link.Up, emptyMap()) shouldBe
            listOf(ServiceRow("YT", Mark.Checking, "🇳🇱 NL-7", "gRPC", listOf(Note("проверяю…", Tone.Warn)), searching = false, mini = Note("проверяю", Tone.Warn)))
    }

    test("отказ в VPN: вероятная причина — постоянный VPN другого приложения") {
        rowsOf(board(Button.Idle, CardStatus.Unknown), Link.Refused, emptyMap()) shouldBe listOf(
            ServiceRow(
                "", null, "VPN не разрешён", null,
                listOf(
                    Note("без разрешения подбирать нечем", Tone.Fail),
                    Note("если включён постоянный VPN другого приложения — выключи его в настройках Android", Tone.Muted),
                ),
                searching = false,
            ),
        )
    }

    test("строка сервиса несёт его секундомер") {
        val b = Board(
            NetworkCard.Measuring(mts), Button.Searching,
            listOf(
                ServiceCard(Telegram.id, "TG", CardStatus.Found(nl, 900, ""), Stopwatch.Stopped(100, 900)),
                ServiceCard(Instagram.id, "IG", CardStatus.Searching, Stopwatch.Running(100)),
            ),
        )
        rowsOf(b, Link.Up, emptyMap()).map { it.stopwatch } shouldBe listOf(Stopwatch.Running(100), Stopwatch.Stopped(100, 900))
    }

    test("время: на кнопке — м:сс, на карточке — секунды с десятыми, дальше минуты") {
        buttonTime(0) shouldBe "0:00"
        buttonTime(8_999) shouldBe "0:08"
        buttonTime(65_000) shouldBe "1:05"
        cardTime(0) shouldBe "0,0 с"
        cardTime(8_349) shouldBe "8,3 с"
        cardTime(59_999) shouldBe "59,9 с"
        cardTime(65_400) shouldBe "1:05"
    }

    test("байты — десятичными единицами: до 100 — с десятой через запятую (нулевую не пишем), дальше целым") {
        bytesText(999) shouldBe "999 Б"
        bytesText(9_800) shouldBe "9,8 КБ"
        bytesText(12_000) shouldBe "12 КБ"
        bytesText(1_000) shouldBe "1 КБ"
        bytesText(192_000) shouldBe "192 КБ"
        bytesText(32_200_000) shouldBe "32,2 МБ"
        bytesText(4_560_000_000) shouldBe "4,6 ГБ" // округляем, как «Использование данных» Android
        bytesText(32_249_999) shouldBe "32,2 МБ"
        bytesText(32_250_000) shouldBe "32,3 МБ"
        bytesText(99_960) shouldBe "100 КБ" // 99,96 → 100,0: уже целым
        bytesText(999_600) shouldBe "1 МБ" // 999,6 КБ → 1000 КБ: следующая единица, а не «1000 КБ»
        bytesText(999_400) shouldBe "999 КБ"
        rateText(340_000) shouldBe "340 КБ/с"
        rateText(0) shouldBe "0 Б/с"
    }

    test("строка пульса — только величины: темп, «ответ N с назад»; простой — «сейчас не пользуется»; ⊥ — строки нет") {
        pulseLine(PairPulse.Seen(340_000, 12_000, Since.Ago(2_000))) shouldBe "↓ 340 КБ/с · ↑ 12 КБ/с · ответ 2 с назад"
        pulseLine(PairPulse.Seen(0, 2_000, Since.Ago(14_000))) shouldBe "↓ 0 Б/с · ↑ 2 КБ/с · ответ 14 с назад"
        pulseLine(PairPulse.Seen(5_000, 0, Since.Never)) shouldBe "↓ 5 КБ/с · ↑ 0 Б/с"
        pulseLine(PairPulse.Seen(0, 0, Since.Never)) shouldBe "сейчас не пользуется"
        pulseLine(PairPulse.Seen(0, 0, Since.Ago(90_000))) shouldBe "сейчас не пользуется"
        pulseLine(PairPulse.Seen(1_000, 0, Since.Ago(125_000))) shouldBe "↓ 1 КБ/с · ↑ 0 Б/с · ответ 2 мин назад"
        pulseLine(PairPulse.Unseen) shouldBe null
    }

    test("трафик сессии: ↓ первым, как «in | out»") {
        trafficLine(Traffic(up = 192_000, down = 32_200_000)) shouldBe "↓ 32,2 МБ · ↑ 192 КБ"
    }

    test("«СЕТЬ» — итог на этой сети: только если ключ итога — ключ сети на экране, и без VPN тоже") {
        val onCell = PulseView(emptyMap(), network = Tally("cell:25001", Traffic(up = 192_000, down = 32_200_000)), vpn = null)
        networkTraffic(onCell, NetworkCard.Measured(mts, Scenario.MobileOpen, "")) shouldBe "↓ 32,2 МБ · ↑ 192 КБ"
        networkTraffic(onCell, NetworkCard.Measuring(mts)) shouldBe "↓ 32,2 МБ · ↑ 192 КБ"
        networkTraffic(onCell, NetworkCard.Measuring(OsNetwork.Wifi)) shouldBe null
        networkTraffic(onCell, NetworkCard.Measuring(OsNetwork.None)) shouldBe null
        networkTraffic(PulseView.Empty, NetworkCard.Measuring(mts)) shouldBe null
    }

    test("«ТЕКУЩИЙ VPN» — итог через VPN на этой подписке; итога нет — нет и строки") {
        vpnTraffic(PulseView(emptyMap(), network = null, vpn = Tally("k", Traffic(up = 40_000, down = 1_250_000)))) shouldBe "↓ 1,3 МБ · ↑ 40 КБ"
        vpnTraffic(PulseView.Empty) shouldBe null
    }

    test("пульс — у строки с сервером; поиск, тупик и VPN не поднят — без пульса") {
        val seen = PulseView(mapOf(Telegram.id to PairPulse.Seen(340_000, 12_000, Since.Ago(2_000)), Instagram.id to PairPulse.Seen(0, 0, Since.Never)), null, null)
        val up = rowsOf(board(Button.Green, CardStatus.Works(nl), CardStatus.Checking(nlx)), Link.Up, emptyMap(), pulse = seen)
        up.single { it.label == "TG" }.pulse shouldBe "↓ 340 КБ/с · ↑ 12 КБ/с · ответ 2 с назад"
        up.single { it.label == "IG" }.pulse shouldBe "сейчас не пользуется"
        rowsOf(board(Button.Searching, CardStatus.Searching, CardStatus.NotFound(listOf("Нет сети."))), Link.Up, emptyMap(), pulse = seen)
            .map { it.pulse } shouldBe listOf(null, null)
        rowsOf(board(Button.Green, CardStatus.Found(nl, 0, "проверено 1 из 9")), Link.Up, emptyMap(), pulse = seen)
            .single { it.label == "TG" }.pulse shouldBe "↓ 340 КБ/с · ↑ 12 КБ/с · ответ 2 с назад"
        rowsOf(board(Button.Green, CardStatus.Works(nl)), Link.Off, emptyMap(), pulse = seen).map { it.pulse } shouldBe listOf(null)
    }
})
