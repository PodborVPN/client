package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PassportTest : FunSpec({

    fun facts(country: String) = ServerFacts(
        Transport.Xhttp, Entry.Ru, WhiteHint.Likely, Exit.At("1.1.1.1", country), Seen.Answered(5),
    )

    test("merged: ключи newer заменяют, прочие остаются") {
        val old = Passport(mapOf("a" to facts("DE"), "b" to facts("NL")), 1)
        val new = Passport(mapOf("b" to facts("FI"), "c" to facts("SE")), 2)
        old.merged(new) shouldBe Passport(mapOf("a" to facts("DE"), "b" to facts("FI"), "c" to facts("SE")), 2)
    }
    test("JSON-кругооборот") {
        val p = Passport(mapOf("a" to facts("DE"), "b" to ServerFacts(Transport.Ws, Entry.Unknown, WhiteHint.Unlikely, Exit.Unknown, Seen.Silent)), 7)
        val json = Json { encodeDefaults = true }
        json.decodeFromString<Passport>(json.encodeToString(p)) shouldBe p
    }
    test("паспорт без sniWhite (старый формат) читается: false") {
        val old = """{"servers":{"a":{"transport":{"type":"podbor.core.Transport.Xhttp"},"entry":{"type":"podbor.core.Entry.Ru"},"whiteHint":{"type":"podbor.core.WhiteHint.Likely"},"exit":{"type":"podbor.core.Exit.Unknown"},"seen":{"type":"podbor.core.Seen.Silent"}}},"analyzedAtMs":1}"""
        Json.decodeFromString<Passport>(old).servers.getValue("a").sniWhite shouldBe false
    }
    test("Server без sni (старая Лента) декодируется") {
        val old = """{"id":"s001","name":"n","endpoint":{"host":"h","port":1},"transport":{"type":"podbor.core.Transport.Xhttp"},"fingerprint":null,"entry":{"type":"podbor.core.Entry.Ru"},"key":"k"}"""
        val s = Json.decodeFromString<Server>(old)
        s.sni shouldBe null
    }
})
