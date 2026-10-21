package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Приложение только вертикальное — экран A в альбомной раскладке не живёт. */
class ManifestTest : FunSpec({
    test("у приложения адаптивная иконка: обычная и круглая, с монохромным слоем") {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val app = doc.getElementsByTagName("application").item(0) as Element
        app.getAttributeNS(android, "icon") shouldBe "@mipmap/ic_launcher"
        app.getAttributeNS(android, "roundIcon") shouldBe "@mipmap/ic_launcher_round"
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val icon = factory.newDocumentBuilder().parse(File("src/main/res/mipmap-anydpi-v26/$name.xml")).documentElement
            listOf("background", "foreground", "monochrome").forEach { icon.getElementsByTagName(it).length shouldBe 1 }
        }
    }

    test("главная Activity — только портрет") {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == "podbor.app.ui.MainActivity" }
        main.getAttributeNS(android, "screenOrientation") shouldBe "portrait"
    }

    test("главная Activity — одна на задачу: повторное открытие не кладёт новый экран поверх старого") {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(android, "name") == "podbor.app.ui.MainActivity" }
        main.getAttributeNS(android, "launchMode") shouldBe "singleTask"
    }

    test("FileProvider Ленты: authority \${applicationId}.tape, не экспортирован, выдаёт права по URI; пути — только cache/share/") {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val providers = doc.getElementsByTagName("provider")
        val p = (0 until providers.length).map { providers.item(it) as Element }.single()
        p.getAttributeNS(android, "name") shouldBe "androidx.core.content.FileProvider"
        p.getAttributeNS(android, "authorities") shouldBe "\${applicationId}.tape"
        p.getAttributeNS(android, "exported") shouldBe "false"
        p.getAttributeNS(android, "grantUriPermissions") shouldBe "true"
        val meta = p.getElementsByTagName("meta-data").item(0) as Element
        meta.getAttributeNS(android, "resource") shouldBe "@xml/tape_paths"

        val paths = factory.newDocumentBuilder().parse(File("src/main/res/xml/tape_paths.xml")).documentElement
        val entries = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }.filterIsInstance<Element>()
        entries.map { Triple(it.tagName, it.getAttribute("name"), it.getAttribute("path")) } shouldBe
            listOf(Triple("cache-path", "share", "share/"))
    }

    test("разрешения не прибавились: «поделиться» идёт через системный шаринг") {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val perms = doc.getElementsByTagName("uses-permission")
        (0 until perms.length).map { (perms.item(it) as Element).getAttributeNS(android, "name").substringAfterLast('.') }.toSet() shouldBe
            setOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION",
                "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS")
    }
})
