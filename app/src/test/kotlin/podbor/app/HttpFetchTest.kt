package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.net.HttpFetch
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val LINK = "https://example.test/releases/latest"

private class FakeConnection(url: URL, private val code: Int) : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy() = false
    override fun getResponseCode() = code
    override fun getHeaderField(name: String): String = "https://example.test/releases/tag/v1.2.3"
    override fun getHeaderFields(): MutableMap<String, MutableList<String>> = mutableMapOf("X-Left" to mutableListOf("7"))
    override fun getInputStream(): InputStream = ByteArrayInputStream("тело".toByteArray())
}

class HttpFetchTest : FunSpec({

    test("сбой при открытии соединения — null и вид сбоя в журнале, без ссылки") {
        val warned = mutableListOf<String>()
        val http = HttpFetch(direct = { throw IOException(it.toString()) }, warn = { warned += it })

        http.download(LINK) shouldBe null
        http.location(LINK, null) shouldBe null

        warned shouldBe listOf("не скачалось: IOException", "не скачалось: IOException")
    }

    test("ссылка, из которой не собрать URL, — null, а не исключение") {
        val http = HttpFetch(direct = { error("сюда не дойти") }, warn = {})

        http.download("без схемы") shouldBe null
        http.location("без схемы", null) shouldBe null
    }

    test("нижележащей сети нет — null без записи о сбое") {
        val warned = mutableListOf<String>()
        val http = HttpFetch(direct = { null }, warn = { warned += it })

        http.download(LINK) shouldBe null
        http.location(LINK, null) shouldBe null

        warned shouldBe emptyList()
    }

    test("location: Location ответа 3xx, редирект не выполняется") {
        lateinit var opened: FakeConnection
        val http = HttpFetch(direct = { url -> FakeConnection(url, 302).also { opened = it } }, warn = {})

        http.location(LINK, null) shouldBe "https://example.test/releases/tag/v1.2.3"

        opened.instanceFollowRedirects shouldBe false
    }

    test("download: заголовки запроса уходят, тело и заголовки ответа возвращаются") {
        lateinit var opened: FakeConnection
        val http = HttpFetch(direct = { url -> FakeConnection(url, 200).also { opened = it } }, warn = {})

        val got = checkNotNull(http.download(LINK, mapOf("X-Hwid" to "abc")))

        got.body shouldBe "тело"
        got.headers shouldBe mapOf("x-left" to "7")
        opened.getRequestProperty("X-Hwid") shouldBe "abc"
    }
})
