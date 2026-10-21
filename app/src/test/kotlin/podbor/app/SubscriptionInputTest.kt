package podbor.app

import arrow.core.Either
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.net.readCapped
import podbor.app.sub.Fetched
import podbor.app.sub.InputError
import podbor.app.sub.SubscriptionInput
import podbor.app.sub.fetchText
import podbor.app.sub.inputOf
import podbor.app.sub.unwrapTranslated
import podbor.app.sub.viaYandex
import podbor.app.sub.wordsOf

class SubscriptionInputTest : FunSpec({
    test("http(s) одной строкой — ссылка, пробелы по краям срезаются") {
        inputOf("https://example.org/sub?t=1") shouldBe Either.Right(SubscriptionInput.Link("https://example.org/sub?t=1"))
        inputOf("  http://example.org/x \n") shouldBe Either.Right(SubscriptionInput.Link("http://example.org/x"))
    }
    test("всё остальное — текст") {
        inputOf("vless://a@b:1#x\nvless://c@d:2#y") shouldBe Either.Right(SubscriptionInput.Text("vless://a@b:1#x\nvless://c@d:2#y"))
        inputOf("https://a.b/1 https://c.d/2") shouldBe Either.Right(SubscriptionInput.Text("https://a.b/1 https://c.d/2"))
        inputOf("https://a.b/1\nhttps://c.d/2") shouldBe Either.Right(SubscriptionInput.Text("https://a.b/1\nhttps://c.d/2"))
    }
    test("ссылка, которую не разобрать как URI (кириллический хост, запрещённые знаки), — Unreadable, а не «сеть не отвечает»") {
        inputOf("https://пример.рф/sub") shouldBe Either.Left(InputError.Unreadable)
        inputOf("https://a.b/sub|x") shouldBe Either.Left(InputError.Unreadable)
        inputOf("https://a.b/sub?t=\"1\"") shouldBe Either.Left(InputError.Unreadable)
        inputOf("https://a.b/подписка") shouldBe Either.Right(SubscriptionInput.Link("https://a.b/подписка"))
    }
    test("подчёркивание в хосте — ссылка (URI.host у такого null, а провайдеры так называют поддомены)") {
        inputOf("https://my_sub.example.com/x") shouldBe Either.Right(SubscriptionInput.Link("https://my_sub.example.com/x"))
        inputOf("https://u:p@my_sub.example.com:8443/x") shouldBe Either.Right(SubscriptionInput.Link("https://u:p@my_sub.example.com:8443/x"))
        inputOf("https://_/x") shouldBe Either.Right(SubscriptionInput.Link("https://_/x"))
    }
    test("пусто и одни пробелы — Empty") {
        inputOf("") shouldBe Either.Left(InputError.Empty)
        inputOf(" \n\t ") shouldBe Either.Left(InputError.Empty)
    }
    test("ссылка: скачали — отдаём тело") {
        fetchText(SubscriptionInput.Link("https://a.b"), { Fetched("тело") }, { false }) shouldBe Either.Right(Fetched("тело"))
    }
    test("ссылка: не скачалась при белых списках — Download(true), иначе Download(false)") {
        fetchText(SubscriptionInput.Link("https://a.b"), { null }, { true }) shouldBe Either.Left(InputError.Download(true))
        fetchText(SubscriptionInput.Link("https://a.b"), { null }, { false }) shouldBe Either.Left(InputError.Download(false))
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
        wordsOf(InputError.NoServers) shouldBe "не нашёл поддерживаемых серверов"
        wordsOf(InputError.ScanFailed) shouldBe "не смог проверить подписку — попробуй ещё раз"
        wordsOf(InputError.Empty) shouldBe "в буфере нет подписки"
        wordsOf(InputError.Unreadable) shouldBe "в буфере нет подписки"
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

    test("белые: сначала туннель найденного выхода, потом прокси Яндекса; открытая сеть — туннель не трогаем") {
        val asked = mutableListOf<String>()
        val down: suspend (String) -> Fetched? = { asked += it; if ("translate.yandex" in it) Fetched("vless://яндекс") else null }
        fetchText(SubscriptionInput.Link("https://a.b"), down, { true }, { Fetched("vless://туннель") }) shouldBe Either.Right(Fetched("vless://туннель"))
        asked shouldBe listOf("https://a.b")
        fetchText(SubscriptionInput.Link("https://a.b"), down, { true }, { null }) shouldBe Either.Right(Fetched("vless://яндекс"))
        fetchText(SubscriptionInput.Link("https://a.b"), { null }, { false }, { error("не должно") }) shouldBe Either.Left(InputError.Download(false))
    }
})
