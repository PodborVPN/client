@file:UseSerializers(arrow.core.serialization.NonEmptyListSerializer::class)

package podbor.core

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MemoryTest : FunSpec({

    val key = MemoryKey(operator = "MTS", scenario = Scenario.MobileWhitelist)

    test("память переживает запись и чтение") {
        val memory = Memory(
            successes = mapOf(key to nonEmptyListOf(Success("a", 2L), Success("b", 1L))),
            blocks = emptyMap(),
        )
        val format = Json { allowStructuredMapKeys = true }
        val json = format.encodeToString(memory)
        format.decodeFromString<Memory>(json) shouldBe memory
    }

    test("тот же сервер не дублируется и встаёт первым") {
        val a = server("s001", Entry.Ru)
        val b = server("s002", Entry.Ru)
        val memory = Memory.Empty
            .rememberSuccess(key, a, 1L)
            .rememberSuccess(key, b, 2L)
            .rememberSuccess(key, a, 3L)
        val remembered: Nel<Success> = memory.successes.getValue(key)
        remembered.map { it.serverKey } shouldBe listOf(a.key, b.key)
        remembered.head.atMs shouldBe 3L
    }

    test("старая память без service в ключе декодируется как Телега") {
        // Формат до сервисов: ключ — {operator, scenario}; та же настройка Json, что в AndroidStore.
        val old = LegacyMemory(
            successes = mapOf(LegacyKey("MTS", Scenario.MobileWhitelist) to nonEmptyListOf(Success("a", 2L))),
            blocks = emptyMap(),
        )
        val format = Json { allowStructuredMapKeys = true; encodeDefaults = true }
        val text = format.encodeToString(LegacyMemory.serializer(), old)
        val memory = format.decodeFromString<Memory>(text)
        memory.successes.keys.single() shouldBe MemoryKey("MTS", Scenario.MobileWhitelist, ServiceId("tg"))
    }

    test("merged: успехи сервиса из update, прочие из this; блоки — больший срок") {
        val a = server("s001", Entry.Ru)
        val b = server("s002", Entry.Ru)
        val tg = MemoryKey("MTS", Scenario.MobileWhitelist, Telegram.id)
        val ig = MemoryKey("MTS", Scenario.MobileWhitelist, Instagram.id)
        val e = Endpoint("1.1.1.1", 443)
        val mine = Memory.Empty.rememberSuccess(tg, a, 1L).copy(blocks = mapOf(e to 100L))
        val update = Memory.Empty.rememberSuccess(ig, b, 2L).copy(blocks = mapOf(e to 200L))
        val result = mine.merged(update, Instagram.id)
        result.successes[tg]!!.first().serverKey shouldBe a.key
        result.successes[ig]!!.first().serverKey shouldBe b.key
        result.blocks[e] shouldBe 200L
        // update без ключа Телеги не стирает её успехи; чужие ключи update не берутся.
        result.successes.keys shouldBe setOf(tg, ig)
        mine.merged(update, Telegram.id).successes.keys shouldBe emptySet()
    }
})

@kotlinx.serialization.Serializable
private data class LegacyKey(val operator: String, val scenario: Scenario)

@kotlinx.serialization.Serializable
private data class LegacyMemory(val successes: Map<LegacyKey, Nel<Success>>, val blocks: Map<Endpoint, Long>)
