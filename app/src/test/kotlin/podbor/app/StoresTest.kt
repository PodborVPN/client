package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import podbor.app.sub.FilePassportStore
import podbor.app.sub.FileTrafficBookStore
import podbor.app.sub.Subscription
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.subscriptionKey
import podbor.core.Tally
import podbor.core.Traffic
import podbor.core.TrafficBook
import podbor.core.Entry
import podbor.core.Exit
import podbor.core.Passport
import podbor.core.Seen
import podbor.core.ServerFacts
import podbor.core.Transport
import podbor.core.WhiteHint
import java.nio.file.Files

class StoresTest : FunSpec({
    fun tmp() = Files.createTempDirectory("stores").toFile()
    val passport = Passport(
        mapOf(
            "a" to ServerFacts(Transport.RealityVision, Entry.Ru, WhiteHint.Likely, Exit.At("1.2.3.4", "DE"), Seen.Answered(5)),
            "b" to ServerFacts(Transport.Other("kcp"), Entry.Unknown, WhiteHint.Unlikely, Exit.Unknown, Seen.Silent),
        ),
        analyzedAtMs = 99,
    )

    test("подписка: кругооборот, без файла — null, битый файл — null") {
        val dir = tmp()
        val store = SubscriptionStore(dir)
        store.load() shouldBe null
        store.save(Subscription("https://a.b/s", "vless://x")); store.load() shouldBe Subscription("https://a.b/s", "vless://x")
        store.save(Subscription(null, "т")); store.load() shouldBe Subscription(null, "т")
        dir.resolve("subscription.json").writeText("{ не json")
        store.load() shouldBe null
    }
    test("паспорт: кругооборот, без файла — null, битый файл — null") {
        val dir = tmp()
        val store = FilePassportStore(dir)
        store.load() shouldBe null
        store.save(passport); store.load() shouldBe passport
        dir.resolve("passport.json").writeText("")
        store.load() shouldBe null
    }
    test("итоги трафика: переживают перезапуск; без файла и битый файл — пустые") {
        val dir = tmp()
        FileTrafficBookStore(dir).load() shouldBe TrafficBook()
        val book = TrafficBook(network = Tally("cell:25001", Traffic(192_000, 32_200_000)), vpn = Tally("k", Traffic(1, 2)))
        FileTrafficBookStore(dir).save(book)
        FileTrafficBookStore(dir).load() shouldBe book
        dir.resolve("traffic.json").writeText("{ не json")
        FileTrafficBookStore(dir).load() shouldBe TrafficBook()
    }
    test("ключ подписки: по ссылке (обновление текста не сбрасывает итог), без ссылки — по тексту; сама ссылка в ключ не попадает") {
        val k = subscriptionKey(Subscription("https://a.b/s", "vless://x"))
        subscriptionKey(Subscription("https://a.b/s", "vless://y")) shouldBe k
        subscriptionKey(Subscription("https://a.b/t", "vless://x")) shouldNotBe k
        subscriptionKey(Subscription(null, "vless://x")) shouldNotBe subscriptionKey(Subscription(null, "vless://y"))
        k.length shouldBe 16
        k.contains("a.b") shouldBe false
    }
    test("запись атомарна: временных файлов не остаётся") {
        val dir = tmp()
        FilePassportStore(dir).save(passport)
        dir.list()!!.toList() shouldBe listOf("passport.json")
    }
    test("сбой записи не бросает") {
        val dir = tmp()
        dir.resolve("passport.json").mkdirs() // на месте файла каталог — rename невозможен
        val warns = mutableListOf<String>()
        FilePassportStore(dir, warns::add).save(passport)
        SubscriptionStore(tmp().also { it.deleteRecursively() }, warns::add).save(Subscription(null, "x"))
        warns.size shouldBe 1 // каталог-на-месте-файла: сбой; второй save создаёт каталог сам и пишет
    }
})
