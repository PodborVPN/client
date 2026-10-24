package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * «Долбит»: сервис через свою пару открывает к одному адресу больше [HAMMER_CONNS] соединений за окно — проба ✓, а
 * приложение не держит соединение и переподключается по кругу. Здоровый разгон — до единиц к адресу.
 */
class HammerTest : FunSpec({

    val dc = Dest("tcp", "149.154.167.51", 443)
    val other = Dest("tcp", "149.154.175.50", 443)
    val pair = "pair.tg.s001"
    fun window(atMono: Long, vararg conns: Conns) = ConnSample(1, atMono, atMono, 10_000, conns.toList())

    test("к одному адресу через пару сервиса — порог и выше: долбит; самое частое — в ответе") {
        val samples = listOf(window(100_000, Conns(pair, dc, 45), Conns(pair, other, 3)))
        hammerOf(samples, pair, nowMonoMs = 105_000) shouldBe Hammer.Hammering(dc, 45, 10_000)
    }

    test("здоровый разгон: много адресов понемногу — спокойно") {
        val calm = (1..14).map { Conns(pair, Dest("tcp", "149.154.167.$it", 443), 7) }
        hammerOf(listOf(window(100_000, *calm.toTypedArray())), pair, nowMonoMs = 105_000) shouldBe Hammer.Calm
        hammerOf(listOf(window(100_000, Conns(pair, dc, HAMMER_CONNS - 1))), pair, nowMonoMs = 105_000) shouldBe Hammer.Calm
    }

    test("долбило через прежний выход — новый не виноват") {
        hammerOf(listOf(window(100_000, Conns("pair.tg.s002", dc, 45))), pair, nowMonoMs = 105_000) shouldBe Hammer.Calm
        hammerOf(listOf(window(100_000, Conns("pair.insta.s001", dc, 45))), pair, nowMonoMs = 105_000) shouldBe Hammer.Calm
    }

    test("старое окно (дольше двух окон назад) — уже не про сейчас; из свежих — последнее долбящее") {
        hammerOf(listOf(window(100_000, Conns(pair, dc, 45))), pair, nowMonoMs = 125_001) shouldBe Hammer.Calm
        val samples = listOf(window(100_000, Conns(pair, dc, 45)), window(110_000, Conns(pair, other, 30)))
        hammerOf(samples, pair, nowMonoMs = 112_000) shouldBe Hammer.Hammering(other, 30, 10_000)
        hammerOf(emptyList(), pair, nowMonoMs = 0) shouldBe Hammer.Calm
    }

    test("слова: сколько раз за сколько секунд, к одному адресу, и вывод — сервер не тянет") {
        hammerWords(Telegram, Hammer.Hammering(dc, 45, 10_000)) shouldBe
            "Телега 45 раз за 10 с переподключается к одному адресу — через этот сервер не тянет"
    }
})
