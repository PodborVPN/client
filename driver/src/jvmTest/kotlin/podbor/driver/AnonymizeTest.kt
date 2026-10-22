package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Значения полей, которые выдают подписку: адреса, ключи, SNI, имена серверов — из исходной Ленты. */
private fun secretsOf(tape: String): Set<String> {
    val out = HashSet<String>()
    fun walk(e: JsonElement) {
        when (e) {
            is JsonArray -> e.forEach(::walk)
            is JsonObject -> e.forEach { (k, v) ->
                if (v is JsonPrimitive && v.isString && (k in setOf("host", "ip", "key", "serverKey", "sni") || (k == "name" && "endpoint" in e))) out += v.content
                if (Regex("[0-9a-f]{16}").matches(k)) out += k
                walk(v)
            }
            is JsonPrimitive -> if (e.isString && Regex("[0-9a-f]{16}").matches(e.content)) out += e.content
        }
    }
    tape.lines().filter { it.isNotBlank() }.forEach { runCatching { walk(Json.parseToJsonElement(it)) } }
    return out
}

/** Вид псевдонимов [anonymize]: адрес, ключ, SNI, имя сервера. */
private val PSEUDONYM = Regex("""h\d+|k\d{15}|sni\d+|Сервер №\d+""")

class AnonymizeTest : FunSpec({
    test("Лента v1, анализ и нажатие двух трубопроводов: после анонимизации ни одного адреса, ключа, SNI и имени") {
        val tape = (resource("fixtures/tape/v1-open-press.jsonl").lines() + scanTape() + pipesTape()).joinToString("\n")
        // Фикстура уже анонимна: её значения — сами псевдонимы, а псевдонимы выгрузка раздаёт заново; утечка — только настоящее.
        val secrets = secretsOf(tape).filterNot { PSEUDONYM.matches(it) }
        (secrets.size > 5) shouldBe true
        val anon = anonymize(tape)
        secrets.filter { it in anon }.shouldBeEmpty()
    }

    test("анонимная Лента проигрывается так же, как исходная: те же прогоны, все сходятся") {
        val tape = (resource("fixtures/tape/v1-open-press.jsonl").lines() + scanTape() + pipesTape()).joinToString("\n")
        val before = replay(tape)
        val after = replay(anonymize(tape))
        after.map { it.machine to it.service } shouldBe before.map { it.machine to it.service }
        after.forEach { it.outcome.shouldBeInstanceOf<Replayed.Converged>().ending shouldBe Ending.Done }
    }

    test("один адрес — один псевдоним во всех строках; IPv4 в тексте ноты — тем же; оборванная строка выброшена") {
        val tape = """
            {"letter":{"endpoint":{"host":"203.0.113.7","port":443},"key":"0123456789abcdef","name":"🇳🇱 Мой-1","sni":"my.provider.ru"}}
            {"blocks":[{"host":"203.0.113.7","port":443}],"note":"setExit tg: dial 203.0.113.7:443 refused","hints":{"0123456789abcdef":1},"sniWhite":["0123456789abcdef"]}
            {"letter":{"endpoint":{"host":"203.0
        """.trimIndent()
        val out = anonymize(tape).lines().filter { it.isNotBlank() }
        out.size shouldBe 2
        out[0] shouldContain "\"host\":\"h1\""
        out[0] shouldContain "\"name\":\"Сервер №1\""
        out[0] shouldContain "\"sni\":\"sni1\""
        out[1] shouldContain "\"host\":\"h1\""
        out[1] shouldContain "dial h1:443 refused"
        out[1] shouldContain "\"k000000000000001\":1"
        out[1] shouldContain "\"sniWhite\":[\"k000000000000001\"]"
        out.joinToString() shouldNotContain "203.0.113.7"
        out.joinToString() shouldNotContain "my.provider.ru"
    }

    test("вышка: cellId обнулён, диапазон и сигнал остаются; оператор — как был") {
        val tape = """{"os":{"type":"podbor.core.OsNetwork.Cellular","operator":"MTS","mccMnc":"25001","tower":{"type":"podbor.core.Tower.Known","cellId":123456789,"earfcn":1602,"rsrpDbm":-97}}}"""
        anonymize(tape).trim() shouldBe
            """{"os":{"type":"podbor.core.OsNetwork.Cellular","operator":"MTS","mccMnc":"25001","tower":{"type":"podbor.core.Tower.Known","cellId":0,"earfcn":1602,"rsrpDbm":-97}}}"""
    }
})
