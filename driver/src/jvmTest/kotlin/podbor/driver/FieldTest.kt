package podbor.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import podbor.core.LineObservation
import podbor.core.RoundKind
import podbor.core.roundOf

/*
 * ФИКСТУРЫ ЛЕНТ: реальные Ленты как были (fixtures/tape/). Каждый тест держит вердикт таким, каким он был на живой сети;
 * правка, меняющая его, обязана тест перевернуть и сказать об этом в коммите.
 */

/** Строка Ленты v0 — только то, что читают фикстуры: момент и буква (у LineObserved — раунд целиком). */
@Serializable
private data class V0Row(val at: Long? = null, val letter: V0Letter? = null)

/** Буква v0 записана редакцией (`type` — короткое имя), не сериализатором Letter. */
@Serializable
private data class V0Letter(val type: String, val line: LineObservation? = null)

private val v0 = Json { ignoreUnknownKeys = true }

/** Ресурс теста строкой. */
internal fun resource(path: String): String =
    requireNotNull(FieldTest::class.java.getResourceAsStream("/$path")) { "нет ресурса $path" }.use { it.readBytes().decodeToString() }

/** Раунды линии Ленты v0 по моменту строки. */
private fun fieldRounds(name: String): Map<Long, LineObservation> =
    resource("fixtures/tape/$name").lineSequence().filter { it.isNotBlank() }
        .map { v0.decodeFromString(V0Row.serializer(), it) }
        .mapNotNull { row -> row.at?.let { at -> row.letter?.line?.let { at to it } } }
        .toMap()

class FieldTest : FunSpec({

    test("Лента v0: раунд Wi-Fi — Unclear, раунд мобильной сети — Open (RU молчат ~305 мс — потолок края)") {
        val rounds = fieldRounds("v0-open.jsonl")
        roundOf(rounds.getValue(1792434056709)) shouldBe RoundKind.Unclear
        roundOf(rounds.getValue(1792434078307)) shouldBe RoundKind.Open
    }
})
