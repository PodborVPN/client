package podbor.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.checkAll

class BoardTest : FunSpec({

    val os = OsNetwork.Cellular("MTS", "25001")
    val ref = ExitRef("NL-7", Transport.Xhttp)
    val net = NetworkCard.Measuring(os)
    fun screen(button: Button, result: ResultCard = ResultCard.Empty, network: NetworkCard = net) = Screen(network, button, result)
    val found = ResultCard.Found("NL-7", Transport.Xhttp, 100, "проверено 2 из 11 входов РФ")
    val notFound = ResultCard.NotFound(listOf("Нет сети."))

    test("карточки по ServiceId: порядок insta, tg, метки IG, TG") {
        val screens = mapOf(Telegram.id to screen(Button.Green, found), Instagram.id to screen(Button.Red, notFound))
        val a = board(setOf(Telegram, Instagram), screens)
        a.cards.map { it.service } shouldBe listOf(Instagram.id, Telegram.id)
        a.cards.map { it.label } shouldBe listOf("IG", "TG")
    }

    test("TG Green + IG Red → Green; карточки Found и NotFound") {
        val b = board(setOf(Telegram, Instagram), mapOf(Telegram.id to screen(Button.Green, found), Instagram.id to screen(Button.Red, notFound)))
        b.button shouldBe Button.Green
        b.cards.map { it.status } shouldBe listOf(CardStatus.NotFound(listOf("Нет сети.")), CardStatus.Found(ref, 100, "проверено 2 из 11 входов РФ"))
    }

    test("кнопка: Searching сильнее всего; Red только если все Red") {
        val s = { b: Button -> screen(b) }
        board(setOf(Telegram, Instagram), mapOf(Telegram.id to s(Button.Searching), Instagram.id to screen(Button.Red, notFound))).button shouldBe Button.Searching
        board(setOf(Telegram, Instagram), mapOf(Telegram.id to s(Button.Searching), Instagram.id to screen(Button.Green, found))).button shouldBe Button.Searching
        board(setOf(Telegram, Instagram), mapOf(Telegram.id to screen(Button.Red, notFound), Instagram.id to screen(Button.Red, notFound))).button shouldBe Button.Red
        board(setOf(Telegram, Instagram), mapOf(Telegram.id to s(Button.Ready), Instagram.id to screen(Button.Red, notFound))).button shouldBe Button.Ready
        board(setOf(Telegram, Instagram), mapOf(Telegram.id to s(Button.Idle), Instagram.id to s(Button.Idle))).button shouldBe Button.Idle
        board(setOf(Telegram, Instagram), emptyMap()).button shouldBe Button.Idle
    }

    test("сервис без экрана — Unknown") {
        val b = board(setOf(Telegram, Instagram), mapOf(Telegram.id to screen(Button.Green, found)))
        b.cards.first { it.service == Instagram.id }.status shouldBe CardStatus.Unknown
    }

    test("отображение ResultCard → CardStatus") {
        fun status(button: Button, r: ResultCard) = board(setOf(Telegram), mapOf(Telegram.id to screen(button, r))).cards.single().status
        status(Button.Searching, ResultCard.Empty) shouldBe CardStatus.Searching
        status(Button.Idle, ResultCard.Empty) shouldBe CardStatus.Unknown
        status(Button.Ready, ResultCard.Exit(ExitState.Off)) shouldBe CardStatus.Unknown
        status(Button.Ready, ResultCard.Exit(ExitState.Unlisted(ServerId("x")))) shouldBe CardStatus.Unlisted(ServerId("x"))
        status(Button.Ready, ResultCard.Exit(ExitState.Checking(ref))) shouldBe CardStatus.Checking(ref)
        status(Button.Ready, ResultCard.Exit(ExitState.Works(ref))) shouldBe CardStatus.Works(ref)
        status(Button.Ready, ResultCard.Exit(ExitState.Dead(ref, Blame.Server))) shouldBe CardStatus.Dead(ref, Blame.Server)
    }

    test("сеть — с экрана GENERAL_FOLLOWS, иначе с первого по ServiceId") {
        val tgNet = NetworkCard.Measuring(OsNetwork.Wifi)
        val igNet = NetworkCard.Measuring(OsNetwork.None)
        board(setOf(Telegram, Instagram), mapOf(Telegram.id to screen(Button.Idle, network = tgNet), Instagram.id to screen(Button.Idle, network = igNet))).network shouldBe tgNet
        board(setOf(Instagram), mapOf(Instagram.id to screen(Button.Idle, network = igNet))).network shouldBe igNet
        board(setOf(Telegram, Instagram), mapOf(Instagram.id to screen(Button.Idle, network = igNet))).network shouldBe igNet
    }

    test("свойство: перестановка набора не меняет Board") {
        val extra = service(ServiceId("zz"), "ZZ", "Зет", probe = ProbeSpec.Instagram)
        val all = listOf(Telegram, Instagram, extra)
        val screens = mapOf(Telegram.id to screen(Button.Green, found), Instagram.id to screen(Button.Red, notFound))
        val expected = board(all.toSet(), screens)
        checkAll(Arb.shuffle(all)) { perm -> board(perm.toSet(), screens) shouldBe expected }
    }
})
