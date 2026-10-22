package podbor.core

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

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

    data class Quiet(val name: String, val rttMs: Long, val silence: Handshake.Silent, val expected: RoundKind)

    // Порог тишины — 10×RTT опоры, но не больше 1 с (край столько не ждёт) и не меньше 300 мс.
    context("тишина заграницы — улика, только если дольше порога") {
        withData(
            nameFn = { it.name },
            Quiet("RTT 80 мс, тишина 700 мс < 10×RTT — медленная сеть, а не блок", 80, Handshake.Silent(Stage.Tls, waitedMs = 700), RoundKind.Unclear),
            Quiet("RTT 80 мс, тишина 800 мс = 10×RTT — похоже на белые списки", 80, Handshake.Silent(Stage.Tls, waitedMs = 800), RoundKind.WhitelistLike),
            Quiet("RTT 200 мс, SYN без ответа 1,5 с — порог ограничен 1 с: белые списки", 200, Handshake.Silent(Stage.Connect, waitedMs = 1_500), RoundKind.WhitelistLike),
            Quiet("RTT 200 мс, тишина 900 мс < 1 с — непонятно", 200, Handshake.Silent(Stage.Tls, waitedMs = 900), RoundKind.Unclear),
            Quiet("RTT 30 мс, тишина 250 мс < 300 мс — не улика даже при быстрой опоре", 30, Handshake.Silent(Stage.Tls, waitedMs = 250), RoundKind.Unclear),
        ) { q -> roundOf(round(three { Handshake.Ok(connectMs = q.rttMs) }, three { q.silence })) shouldBe q.expected }
    }

    test("по эталону на сторону — кворум недостижим") {
        val one = LineObservation(nonEmptyListOf(Sample("ru", Handshake.Ok(30))), nonEmptyListOf(Sample("far", Handshake.Silent(Stage.Connect, 1_500))))
        roundOf(one) shouldBe RoundKind.Unclear
    }

    test("DNS РФ отказал, заграница SYN без ответа → непонятно, не «нет сети» (Unknown не «никто не ответил»)") {
        roundOf(round(three { Handshake.Unknown("dns") }, three { hole })) shouldBe RoundKind.Unclear
    }

    test("«нет сети» — только если обе стороны показали Silent(Connect)/NoRoute") {
        roundOf(round(nonEmptyListOf(hole, Handshake.Unknown("dns"), Handshake.Unknown("dns")), nonEmptyListOf(hole, Handshake.NoRoute, Handshake.Unknown("x")))) shouldBe RoundKind.Offline
        roundOf(round(three { Handshake.Unknown("dns") }, three { Handshake.NoRoute })) shouldBe RoundKind.Unclear
    }

    fun withOwn(ru: NonEmptyList<Handshake>, cdn1: Handshake, cdn2: Handshake, own: Handshake) = LineObservation(
        russia = ru.mapIndexed { i, h -> Sample("ru$i", h) },
        abroad = nonEmptyListOf(Sample("cdn1", cdn1), Sample("cdn2", cdn2), Sample("own", own, Role.Own)),
    )

    test("две CDN отвечают, а свой эталон молчит после TCP → не «открыто»") {
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Silent(Stage.Tls, 1_500))) shouldBe RoundKind.Unclear
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Reset(Stage.Tls))) shouldBe RoundKind.Unclear
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Mirage)) shouldBe RoundKind.Unclear
    }

    test("свой эталон и обе CDN отвечают → открыто") {
        roundOf(withOwn(three { ok }, ok, ok, ok)) shouldBe RoundKind.Open
    }

    test("свой эталон Unknown (DNS, VPS лежит) — не улика: две CDN дают «открыто»") {
        roundOf(withOwn(three { ok }, ok, ok, Handshake.Unknown("ceiling"))) shouldBe RoundKind.Open
    }

    test("свой закрыт и ещё одна CDN закрыта, РФ ✓ → похоже на белые списки") {
        roundOf(withOwn(three { ok }, ok, muted, Handshake.Silent(Stage.Tls, 1_500))) shouldBe RoundKind.WhitelistLike
    }
})
