package podbor.core

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun <T> three(make: (Int) -> T): NonEmptyList<T> = nonEmptyListOf(make(0), make(1), make(2))

/** Раунд линии по сырым исходам рукопожатий. */
class LineRoundTest : FunSpec({

    val ok = Handshake.Ok(connectMs = 30)
    val hole = Handshake.Silent(Stage.Connect, waitedMs = 1_500)
    val muted = Handshake.Silent(Stage.Tls, waitedMs = 1_400)

    fun round(ru: NonEmptyList<Handshake>, far: NonEmptyList<Handshake>) = LineObservation(
        russia = ru.mapIndexed { i, h -> Sample("ru$i", h) },
        abroad = far.mapIndexed { i, h -> Sample("far$i", h) },
    )

    data class Row(val name: String, val ru: NonEmptyList<Handshake>, val far: NonEmptyList<Handshake>, val expected: RoundKind)

    withData(
        nameFn = { it.name },
        Row("заграница 2 из 3 рукопожатий → открыто, даже если РФ молчит", nonEmptyListOf(hole, hole, hole), nonEmptyListOf(ok, ok, muted), RoundKind.Open),
        Row("РФ ✓, заграница: TCP есть, ServerHello нет → похоже на белые списки", nonEmptyListOf(ok, ok, ok), nonEmptyListOf(muted, muted, muted), RoundKind.WhitelistLike),
        Row("РФ ✓, заграница: SYN без ответа → похоже на белые списки", nonEmptyListOf(ok, ok, hole), nonEmptyListOf(hole, hole, ok), RoundKind.WhitelistLike),
        Row("РФ ✓, заграница: сброс и подмена → похоже на белые списки", nonEmptyListOf(ok, ok, ok),
            nonEmptyListOf(Handshake.Reset(Stage.Tls), Handshake.Mirage, ok), RoundKind.WhitelistLike),
        Row("ничего не ответило, только таймауты и нет маршрута → нет сети", nonEmptyListOf(hole, Handshake.NoRoute, Handshake.Unknown("dns")),
            nonEmptyListOf(hole, hole, Handshake.NoRoute), RoundKind.Offline),
        Row("незнание не улика: всё Unknown → непонятно, а не «нет сети»", three { Handshake.Unknown("dns") },
            three { Handshake.Unknown("protect") }, RoundKind.Unclear),
        Row("РФ ✓, заграница не определилась (DNS, отказ) → непонятно", nonEmptyListOf(ok, ok, ok),
            nonEmptyListOf(Handshake.Unknown("dns"), Handshake.Refused, muted), RoundKind.Unclear),
        Row("РФ молчит, заграница молчит, но TCP отвечает → непонятно, не нет сети", nonEmptyListOf(muted, muted, muted),
            nonEmptyListOf(muted, muted, muted), RoundKind.Unclear),
    ) { row -> roundOf(round(row.ru, row.far)) shouldBe row.expected }

    test("тишина короче 10×RTT опоры — не улика: медленная сеть, а не блок") {
        val ru = three { Handshake.Ok(connectMs = 80) } // порог 800 мс
        roundOf(round(ru, three { Handshake.Silent(Stage.Tls, waitedMs = 700) })) shouldBe RoundKind.Unclear
        roundOf(round(ru, three { Handshake.Silent(Stage.Tls, waitedMs = 800) })) shouldBe RoundKind.WhitelistLike
    }

    test("порог тишины ограничен 1 с: при RTT 200 мс SYN без ответа за 1,5 с — белые списки, а не «непонятно»") {
        val ru = three { Handshake.Ok(connectMs = 200) } // 10×RTT = 2 с, но край столько не ждёт
        roundOf(round(ru, three { Handshake.Silent(Stage.Connect, waitedMs = 1_500) })) shouldBe RoundKind.WhitelistLike
        roundOf(round(ru, three { Handshake.Silent(Stage.Tls, waitedMs = 900) })) shouldBe RoundKind.Unclear
    }

    test("по эталону на сторону — кворум недостижим") {
        val one = LineObservation(nonEmptyListOf(Sample("ru", Handshake.Ok(30))), nonEmptyListOf(Sample("far", Handshake.Silent(Stage.Connect, 1_500))))
        roundOf(one) shouldBe RoundKind.Unclear
    }

    test("раунд переживает JSON") {
        Json.decodeFromString<LineObservation>(Json.encodeToString(line(Reach.Up, Reach.Down))) shouldBe line(Reach.Up, Reach.Down)
    }

    test("I2: DNS РФ отказал, заграница SYN без ответа → непонятно, не «нет сети» (Unknown не «никто не ответил»)") {
        roundOf(round(three { Handshake.Unknown("dns") }, three { hole })) shouldBe RoundKind.Unclear
    }

    test("I2: «нет сети» — только если обе стороны показали Silent(Connect)/NoRoute") {
        roundOf(round(nonEmptyListOf(hole, Handshake.Unknown("dns"), Handshake.Unknown("dns")), nonEmptyListOf(hole, Handshake.NoRoute, Handshake.Unknown("x")))) shouldBe RoundKind.Offline
        roundOf(round(three { Handshake.Unknown("dns") }, three { Handshake.NoRoute })) shouldBe RoundKind.Unclear
    }

    fun withOwn(ru: NonEmptyList<Handshake>, cdn1: Handshake, cdn2: Handshake, own: Handshake) = LineObservation(
        russia = ru.mapIndexed { i, h -> Sample("ru$i", h) },
        abroad = nonEmptyListOf(Sample("cdn1", cdn1), Sample("cdn2", cdn2), Sample("own", own, Role.Own)),
    )

    test("I3: две CDN отвечают, а свой эталон молчит после TCP → не «открыто»") {
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Silent(Stage.Tls, 1_500))) shouldBe RoundKind.Unclear
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Reset(Stage.Tls))) shouldBe RoundKind.Unclear
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Mirage)) shouldBe RoundKind.Unclear
    }

    test("I3: свой эталон и обе CDN отвечают → открыто") {
        roundOf(withOwn(three { ok }, ok, ok, ok)) shouldBe RoundKind.Open
    }

    test("I3: свой эталон Unknown (DNS, VPS лежит) — не улика: две CDN дают «открыто»") {
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Unknown("ceiling"))) shouldBe RoundKind.Open
    }

    test("I3: свой закрыт и ещё одна CDN закрыта, РФ ✓ → похоже на белые списки") {
        roundOf(withOwn(three { ok }, ok, muted, Handshake.Silent(Stage.Tls, 1_500))) shouldBe RoundKind.WhitelistLike
    }

    test("тишина короче 300 мс — не улика даже при быстрой опоре") {
        roundOf(round(nonEmptyListOf(ok, ok, ok), three { Handshake.Silent(Stage.Tls, waitedMs = 250) })) shouldBe RoundKind.Unclear
    }
})

/** Подозрение и подтверждение: «белые списки» — только по двум раундам подряд. */
class SuspicionTest : FunSpec({

    val mts = OsNetwork.Cellular("MTS", "25001")
    val wl = line(Reach.Up, Reach.Down)
    val open = line(Reach.Up, Reach.Up)
    val murky = LineObservation(
        russia = three { Sample("ru$it", Handshake.Ok(30)) },
        abroad = three { Sample("far$it", Handshake.Unknown("dns")) },
    )

    data class Row(val name: String, val rounds: NonEmptyList<LineObservation>, val expected: Scenario, val again: Boolean)

    withData(
        nameFn = { it.name },
        Row("один раунд «похоже» → подозрение и второй раунд", nonEmptyListOf(wl), Scenario.SuspectWhitelist, true),
        Row("два раунда «похоже» → белые списки", nonEmptyListOf(wl, wl), Scenario.MobileWhitelist, false),
        Row("«похоже», затем открыто → первый был просадкой", nonEmptyListOf(wl, open), Scenario.MobileOpen, false),
        Row("«похоже», затем непонятно → подозрение остаётся, без гаданий", nonEmptyListOf(wl, murky), Scenario.SuspectWhitelist, false),
        Row("открыто сразу → без второго раунда", nonEmptyListOf(open), Scenario.MobileOpen, false),
        Row("непонятно сразу → без второго раунда", nonEmptyListOf(murky), Scenario.Unclear, false),
    ) { row ->
        scenarioOf(mts, row.rounds) shouldBe row.expected
        needsConfirmation(row.rounds) shouldBe row.again
    }

    test("белый список, увиденный линией на Wi-Fi (раздача), — тоже белый список") {
        scenarioOf(OsNetwork.Wifi, nonEmptyListOf(wl, wl)) shouldBe Scenario.MobileWhitelist
        scenarioOf(OsNetwork.Wifi, nonEmptyListOf(open)) shouldBe Scenario.Wifi
    }
})
