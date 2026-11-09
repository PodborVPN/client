package podbor.screen

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import podbor.core.ExitFilter

class CountriesTest : FunSpec({
    test("изменения черновика не применяются до подтверждения") {
        val original = ExitFilter(setOf("RU"))
        val opened = countryStep(CountrySheet.Closed, CountryInput.Open(original)).state
        val changed = countryStep(opened, CountryInput.Toggle("DE")).state
        countryStep(changed, CountryInput.Dismiss) shouldBe CountryChange(CountrySheet.Closed)
        original.excluded shouldBe setOf("RU")
        countryStep(changed, CountryInput.Apply).applied shouldBe listOf(ExitFilter(setOf("RU", "DE")))
    }
    test("все страны сбрасывает и исключения которых пока нет в подписке") {
        val opened = countryStep(CountrySheet.Closed, CountryInput.Open(ExitFilter(setOf("RU", "BY")))).state
        val all = countryStep(opened, CountryInput.All).state
        countryStep(all, CountryInput.Apply).applied shouldBe listOf(ExitFilter())
    }
})
