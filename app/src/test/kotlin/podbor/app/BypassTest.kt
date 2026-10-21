package podbor.app

import arrow.core.nonEmptyListOf
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import podbor.app.vpn.BYPASS_APPS
import podbor.app.vpn.bypassable
import podbor.core.HARDCODED_SERVICES
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BypassTest : FunSpec({

    test("исключаются только установленные пакеты, порядок списка сохраняется") {
        bypassable(setOf("a", "c"), nonEmptyListOf("a", "b", "c")) shouldBe listOf("a", "c")
    }

    test("ничего не установлено → пусто") {
        bypassable(emptySet(), nonEmptyListOf("a", "b")) shouldBe emptyList()
    }

    test("<queries> манифеста = банки + пакеты всех сервисов (по ServiceId): иначе Android 11+ не покажет пакет и исключение или правило молча пропадёт") {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = doc.getElementsByTagName("queries").item(0) as Element
        val packages = queries.getElementsByTagName("package").let { list ->
            (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") }
        }
        packages shouldBe BYPASS_APPS.toList() + HARDCODED_SERVICES.sortedBy { it.id.id }.flatMap { it.match.apps.sorted() }
        BYPASS_APPS shouldContain "ru.gosuslugi.goskey"
    }
})
