package podbor.app

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import podbor.app.net.readCapped
import podbor.app.sub.Fetched
import podbor.screen.Announce
import podbor.screen.InputError
import podbor.screen.Support
import podbor.screen.SubscriptionInput
import podbor.app.sub.fetchText
import podbor.app.sub.inputOf
import podbor.app.sub.unwrapTranslated
import podbor.app.sub.viaYandex
import podbor.app.sub.wordsOf
import podbor.app.sub.SkippedWords
import podbor.app.sub.skippedWords
import podbor.screen.Skipped

class SubscriptionInputTest : FunSpec({
    /** Вставка → что из неё вышло. */
    data class Case(val name: String, val raw: String, val expected: Either<InputError, SubscriptionInput>)

    fun link(u: String) = Either.Right(SubscriptionInput.Link(u))
    fun text(t: String) = Either.Right(SubscriptionInput.Text(t))
    val twoServers = listOf("vless://a@b:1#x", "vless://c@d:2#y").joinToString("\n")

    context("разбор вставки: ссылка, текст или ошибка") {
        withData(
            nameFn = { it.name },
            Case("http(s) одной строкой — ссылка", "https://example.org/sub?t=1", link("https://example.org/sub?t=1")),
            Case("пробелы по краям срезаются", "  http://example.org/x \n", link("http://example.org/x")),
            Case("кириллический путь — ссылка", "https://a.b/подписка", link("https://a.b/подписка")),
            // URI.host у такого null, а провайдеры так называют поддомены.
            Case("подчёркивание в хосте — ссылка", "https://my_sub.example.com/x", link("https://my_sub.example.com/x")),
            Case("подчёркивание в хосте с паролем и портом — ссылка", "https://u:p@my_sub.example.com:8443/x", link("https://u:p@my_sub.example.com:8443/x")),
            Case("хост из одного подчёркивания — ссылка", "https://_/x", link("https://_/x")),
            Case("несколько серверов — текст", twoServers, text(twoServers)),
            Case("две ссылки через пробел — текст", "https://a.b/1 https://c.d/2", text("https://a.b/1 https://c.d/2")),
            Case("две ссылки строками — текст", "https://a.b/1\nhttps://c.d/2", text("https://a.b/1\nhttps://c.d/2")),
            // Не разобрать как URI — Unreadable, а не «сеть не отвечает».
            Case("кириллический хост — Unreadable", "https://пример.рф/sub", Either.Left(InputError.Unreadable)),
            Case("знак | — Unreadable", "https://a.b/sub|x", Either.Left(InputError.Unreadable)),
            Case("кавычки — Unreadable", "https://a.b/sub?t=\"1\"", Either.Left(InputError.Unreadable)),
            Case("зашифрованная ссылка Happ — HappOnly", "happ://crypt5/QWxhZGRpbjpvcGVu", Either.Left(InputError.HappOnly)),
            Case("Happ любой версии шифра, регистр — HappOnly", " HAPP://crypt3/abc \n", Either.Left(InputError.HappOnly)),
            Case("пусто — Empty", "", Either.Left(InputError.Empty)),
            Case("одни пробелы — Empty", " \n\t ", Either.Left(InputError.Empty)),
        ) { inputOf(it.raw) shouldBe it.expected }
    }
    test("ссылка: скачали — отдаём тело") {
        fetchText(SubscriptionInput.Link("https://a.b"), { Fetched("тело") }, { false }) shouldBe Either.Right(Fetched("тело"))
    }
    test("ссылка: пустое тело — Unreadable") {
        fetchText(SubscriptionInput.Link("https://a.b"), { Fetched("  ") }, { false }) shouldBe Either.Left(InputError.Unreadable)
    }
    test("текст — как есть, без скачивания") {
        fetchText(SubscriptionInput.Text("vless://x"), { error("не должно") }, { error("не должно") }) shouldBe Either.Right(Fetched("vless://x"))
    }
    test("тело подписки: до потолка — текстом UTF-8, длиннее — null") {
        readCapped("vless://сервер".toByteArray().inputStream(), cap = 100) shouldBe "vless://сервер"
        readCapped(ByteArray(20_000) { 'a'.code.toByte() }.inputStream(), cap = 20_000) shouldBe "a".repeat(20_000)
        readCapped(ByteArray(20_001) { 'a'.code.toByte() }.inputStream(), cap = 20_000) shouldBe null
    }
    test("слова ошибок") {
        wordsOf(InputError.Download(true)) shouldBe "не могу скачать подписку: похоже, белые списки. Попроси прислать её текстом или QR в VK или Max — или добавь через Wi-Fi"
        wordsOf(InputError.Download(false)) shouldBe "не могу скачать подписку: сеть не отвечает"
        wordsOf(InputError.NoServers(Skipped(0, mapOf("tuic" to 1), 0))) shouldBe "не нашёл поддерживаемых серверов"
        wordsOf(InputError.NoServers(Skipped(0, emptyMap(), 0))) shouldBe "не узнал формат подписки"
        wordsOf(InputError.ScanFailed) shouldBe "не смог проверить подписку — попробуй ещё раз"
        wordsOf(InputError.Empty) shouldBe "в буфере нет подписки"
        wordsOf(InputError.Unreadable) shouldBe "в буфере нет подписки"
    }

    test("лимит устройств провайдера (x-hwid-limit при 200) — DeviceLimit со словами провайдера, а не пустая подписка") {
        val announce = "base64:" + java.util.Base64.getEncoder().encodeToString("Отвяжи устройство в боте".toByteArray())
        val limit = mapOf(
            "x-hwid-limit" to "true", "x-hwid-max-devices-reached" to "true", "announce" to announce, "support-url" to "https://t.me/help",
        )
        fetchText(SubscriptionInput.Link("https://a.b"), { Fetched("", limit) }, { false }) shouldBe
            Either.Left(InputError.DeviceLimit(Announce.Text("Отвяжи устройство в боте"), Support.Link("https://t.me/help")))
        // Панель с заглушками в теле вместо пустого — тоже отказ, а не серверы-пустышки.
        fetchText(SubscriptionInput.Link("https://a.b"), { Fetched("vless://x", mapOf("x-hwid-limit" to "true")) }, { false }) shouldBe
            Either.Left(InputError.DeviceLimit(Announce.None, Support.None))
    }

    test("прокси Яндекса качает отдельным путём — без заголовков устройства провайдера") {
        val direct = mutableListOf<String>()
        val proxied = mutableListOf<String>()
        fetchText(
            SubscriptionInput.Link("https://a.b"),
            download = { direct += it; null },
            whitelist = { true },
            proxy = { proxied += it; Fetched("vless://яндекс") },
        ) shouldBe Either.Right(Fetched("vless://яндекс"))
        direct shouldBe listOf("https://a.b")
        proxied shouldBe listOf(viaYandex("https://a.b"))
    }

    test("слова ссылки только для Happ: что попросить у провайдера") {
        wordsOf(InputError.HappOnly) shouldBe
            "это зашифрованная ссылка только для приложения Happ — попроси у провайдера обычную ссылку подписки (https://…)"
    }

    test("слова лимита устройств: слова провайдера, если есть") {
        wordsOf(InputError.DeviceLimit(Announce.Text("Отвяжи устройство в боте"), Support.None)) shouldBe
            "лимит устройств подписки: Отвяжи устройство в боте"
        wordsOf(InputError.DeviceLimit(Announce.None, Support.None)) shouldBe
            "лимит устройств подписки исчерпан — отвяжи старое устройство у провайдера"
    }

    test("шторка пропусков: ничего не подошло — чужие схемы по убыванию счёта, битые последними") {
        skippedWords(Skipped(0, mapOf("tuic" to 1, "hysteria2" to 5, "anytls" to 1), broken = 2)) shouldBe SkippedWords(
            title = "Podbor не поддерживает эту подписку",
            lines = listOf("hysteria2 — 5", "anytls — 1", "tuic — 1", "битые ссылки — 2"),
            note = "Podbor понимает vless, vmess, trojan и shadowsocks — попроси у провайдера подписку на них",
        )
    }

    test("шторка пропусков: ссылок нет — формат не узнан") {
        skippedWords(Skipped(0, emptyMap(), 0)) shouldBe SkippedWords(
            title = "Не узнал формат подписки",
            lines = emptyList(),
            note = "Podbor понимает ссылки vless, vmess, trojan и shadowsocks, а это похоже на настройки Clash или sing-box — " +
                "попроси у провайдера подписку для v2rayNG",
        )
    }

    test("шторка пропусков: часть подошла — сколько проверю из всех, остальное пропускаю") {
        skippedWords(Skipped(4, mapOf("hysteria2" to 3), broken = 1)) shouldBe SkippedWords(
            title = "Проверю 4 сервера из 8",
            lines = listOf("hysteria2 — 3", "битая ссылка — 1"),
            note = "остальные Podbor не поддерживает — пропущу",
        )
        skippedWords(Skipped(1, mapOf("tuic" to 1), broken = 0)).title shouldBe "Проверю 1 сервер из 2"
        skippedWords(Skipped(5, mapOf("tuic" to 1), broken = 0)).title shouldBe "Проверю 5 серверов из 6"
    }

    test("белые списки: не скачалась напрямую — вторая попытка через прокси Яндекс Переводчика") {
        val link = "https://my.sub/x?t=1&u=2"
        val asked = mutableListOf<String>()
        val body = fetchText(SubscriptionInput.Link(link), { url -> asked += url; if (url == viaYandex(link)) Fetched("vless://a", mapOf("x" to "яндекс")) else null }, { true })
        body shouldBe Either.Right(Fetched("vless://a"))
        asked shouldBe listOf(link, "https://translate.yandex.ru/translate?url=https%3A%2F%2Fmy.sub%2Fx%3Ft%3D1%26u%3D2&lang=de-de")
    }
    test("открытая сеть — через прокси не ходим; прокси тоже не отдал — Download(true)") {
        val asked = mutableListOf<String>()
        fetchText(SubscriptionInput.Link("https://a.b"), { asked += it; null }, { false }) shouldBe Either.Left(InputError.Download(false))
        asked shouldBe listOf("https://a.b")
        fetchText(SubscriptionInput.Link("https://a.b"), { null }, { true }) shouldBe Either.Left(InputError.Download(true))
    }
    test("ответ прокси: текст — как есть; HTML-обёртка перевода — текст из <pre> с раскрытыми сущностями; иная страница — null") {
        unwrapTranslated("vless://a@b:1?x=1&y=2#имя\n") shouldBe "vless://a@b:1?x=1&y=2#имя\n"
        unwrapTranslated("dmxlc3M6Ly9h") shouldBe "dmxlc3M6Ly9h"
        val page = """<html lang="en"><head><base href="https://x/s.txt"/><style>p{}</style></head><body><pre>vless://a@b:1?x=1&amp;y=2#&lt;NL&gt; &#39;q&#39; &#x41;
vless://c@d:2#<span>имя</span>
</pre></body></html>"""
        unwrapTranslated(page) shouldBe "vless://a@b:1?x=1&y=2#<NL> 'q' A\nvless://c@d:2#имя\n"
        unwrapTranslated("<!DOCTYPE html><html><title>Ошибка перевода</title></html>") shouldBe null
    }

    test("страница прокси: сущность вне Unicode или длиннее Int — знак замены, как в браузере; разбор не падает") {
        fun page(pre: String) = "<html><body><pre>$pre</pre></body></html>"
        unwrapTranslated(page("a&#99999999999;b")) shouldBe "a�b"
        unwrapTranslated(page("a&#xFFFFFFFFFF;b")) shouldBe "a�b"
        unwrapTranslated(page("a&#1114112;b&#x110000;c")) shouldBe "a�b�c"
        unwrapTranslated(page("a&#1114111;b")) shouldBe "a${Character.toChars(0x10FFFF).concatToString()}b"
    }

    test("белые: сначала туннель найденного выхода, потом прокси Яндекса; открытая сеть — туннель не трогаем") {
        val asked = mutableListOf<String>()
        val down: suspend (String) -> Fetched? = { asked += it; if ("translate.yandex" in it) Fetched("vless://яндекс") else null }
        fetchText(SubscriptionInput.Link("https://a.b"), down, { true }, { Fetched("vless://туннель") }) shouldBe Either.Right(Fetched("vless://туннель"))
        asked shouldBe listOf("https://a.b")
        fetchText(SubscriptionInput.Link("https://a.b"), down, { true }, { null }) shouldBe Either.Right(Fetched("vless://яндекс"))
        fetchText(SubscriptionInput.Link("https://a.b"), { null }, { false }, { error("не должно") }) shouldBe Either.Left(InputError.Download(false))
    }
})
