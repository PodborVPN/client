package podbor.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.app.sub.Shelf
import podbor.app.sub.Subscription
import podbor.app.sub.chosen
import podbor.app.sub.current
import podbor.app.sub.put
import podbor.app.sub.updated
import podbor.app.sub.without
import podbor.screen.Analyzed

/** Список подписок: чистые операции — вставка с дублями, выбор, правка, удаление. */
class ShelfTest : FunSpec({
    var n = 0
    fun ids(): () -> String = { "id${++n}" }
    val a = Subscription("https://a.b/s", "vless://a", id = "A")
    val b = Subscription(null, "vless://b", id = "B")
    val two = Shelf(listOf(a, b), currentId = "A")

    test("вставка новой — в конец списка и текущая, с новым id") {
        n = 0
        val (shelf, put) = Shelf().put(Subscription("https://c.d/s", "vless://c"), ids())
        put.id shouldBe "id1"
        shelf shouldBe Shelf(listOf(put), currentId = "id1")
        shelf.current() shouldBe put
    }
    test("повторная вставка той же ссылки — та же запись (id прежний), свежий текст, текущая") {
        val (shelf, put) = two.chosen("B").put(Subscription("https://a.b/s", "vless://a2"), ids())
        put shouldBe a.copy(text = "vless://a2")
        shelf.items shouldBe listOf(put, b)
        shelf.currentId shouldBe "A"
    }
    test("повторная вставка хранит прежнюю отметку анализа — новая придёт с концом анализа") {
        val done = a.copy(analyzed = Analyzed.Done(7))
        val (_, put) = Shelf(listOf(done), "A").put(Subscription("https://a.b/s", "vless://a2"), ids(), nowMs = 9)
        put.analyzed shouldBe Analyzed.Done(7)
    }
    test("ставшая текущей — помечена моментом (вставка и выбор)") {
        two.put(Subscription(null, "vless://c"), ids(), nowMs = 5).second.usedAtMs shouldBe 5
        two.chosen("B", nowMs = 8).items.single { it.id == "B" }.usedAtMs shouldBe 8
    }
    test("без ссылки — дубль по тексту") {
        val (shelf, put) = two.put(Subscription(null, "vless://b"), ids())
        put.id shouldBe "B"
        shelf.items.size shouldBe 2
        shelf.currentId shouldBe "B"
    }
    test("без ссылки другой текст — новая запись") {
        two.put(Subscription(null, "vless://c"), ids()).first.items.size shouldBe 3
    }
    test("chosen меняет только текущую; чужой id — ничего") {
        two.chosen("B") shouldBe two.copy(currentId = "B")
        two.chosen("Z") shouldBe two
    }
    test("updated правит одну запись") {
        two.updated("B") { it.copy(text = "vless://b2") }.items shouldBe listOf(a, b.copy(text = "vless://b2"))
    }
    test("without текущей — текущей нет, прочие целы; without прочей — текущая та же") {
        two.without("A") shouldBe Shelf(listOf(b), currentId = null)
        two.without("B") shouldBe Shelf(listOf(a), currentId = "A")
        two.without("A").current() shouldBe null
    }
})
