package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import podbor.app.sub.FilePassportStore
import podbor.app.sub.FileTrafficBookStore
import podbor.app.sub.Subscription
import podbor.app.sub.SubscriptionStore
import podbor.app.sub.deviceHeaders
import podbor.app.sub.hwidOf
import podbor.app.sub.installSalt
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
        val salt = installSalt(tmp())
        val k = subscriptionKey(Subscription("https://a.b/s", "vless://x"), salt)
        subscriptionKey(Subscription("https://a.b/s", "vless://y"), salt) shouldBe k
        subscriptionKey(Subscription("https://a.b/t", "vless://x"), salt) shouldNotBe k
        subscriptionKey(Subscription(null, "vless://x"), salt) shouldNotBe subscriptionKey(Subscription(null, "vless://y"), salt)
        k.length shouldBe 16
        k.contains("a.b") shouldBe false
    }
    test("HWID: стабилен на установке, у другой — свой; формат панелей (10–64 знака [A-Za-z0-9=-]); соль из него не видна") {
        val salt = installSalt(tmp())
        val id = hwidOf(salt)
        hwidOf(salt) shouldBe id
        hwidOf(installSalt(tmp())) shouldNotBe id
        Regex("^[a-zA-Z0-9=-]{10,64}$").matches(id) shouldBe true
        id.contains(salt.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }) shouldBe false
        // Ключ подписки и HWID — разные производные одной соли.
        subscriptionKey(Subscription(null, "hwid"), salt) shouldNotBe id.take(16)
    }
    test("заголовки устройства для подписки: HWID и сведения о системе") {
        deviceHeaders("0123456789abcdef", osVersion = "16", model = "Redmi Note 14 Pro") shouldBe mapOf(
            "x-hwid" to "0123456789abcdef",
            "x-device-os" to "Android",
            "x-ver-os" to "16",
            "x-device-model" to "Redmi Note 14 Pro",
        )
    }
    test("соль установки: одна на установку, у другой — своя; ключ одной подписки на разных установках не сличить") {
        val dir = tmp()
        installSalt(dir).toList() shouldBe installSalt(dir).toList()
        val other = installSalt(tmp())
        other.toList() shouldNotBe installSalt(dir).toList()
        val s = Subscription("https://a.b/s", "vless://x")
        subscriptionKey(s, installSalt(dir)) shouldNotBe subscriptionKey(s, other)
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
