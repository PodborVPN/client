package podbor.screen

/** Способы поддержки проекта: общий список для телефона и веб-интерфейса. */
enum class Donation(val title: String, val url: String) {
    Boosty("Boosty", "https://boosty.to/stasyanrazrab"),
    Ozon("Ozon Банк", "https://finance.ozon.ru/apps/sbp/ozonbankpay/01a20765-a69c-733f-905f-aee8b98d08ca"),
}
