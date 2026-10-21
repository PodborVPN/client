package podbor.app.vpn

import arrow.core.Nel
import arrow.core.nonEmptyListOf

/**
 * Банки и Госуслуги — мимо VPN: им нужен российский адрес, иностранный выход они режут.
 *
 * Источник имён: `adb shell pm list packages` на тестовом телефоне. Установлены и сверены там: `ru.rostel` (Госуслуги), `ru.gosuslugi.goskey` (Госключ — подпись для Госуслуг),
 * `com.idamob.tinkoff.android` (Т-Банк).
 * Сбер, ВТБ и Альфа на том телефоне не установлены — их имена взяты из адресов карточек Google Play
 * (`play.google.com/store/apps/details?id=…`) и на устройстве не сверены; не установленное пропускается ([bypassable]).
 * Каждое имя продублировано в `<queries>` манифеста: без этого Android 11+ не покажет пакет приложению.
 */
val BYPASS_APPS: Nel<String> = nonEmptyListOf(
    "ru.sberbankmobile", // Сбер — Google Play, не сверено pm list
    "com.idamob.tinkoff.android", // Т-Банк — pm list
    "ru.vtb24.mobilebanking.android", // ВТБ — Google Play, не сверено pm list
    "ru.alfabank.mobile.android", // Альфа — Google Play, не сверено pm list
    "ru.rostel", // Госуслуги — pm list
    "ru.gosuslugi.goskey", // Госключ — pm list
)

/** Из желаемых — только установленные: addDisallowedApplication на неустановленный пакет бросает NameNotFound. */
fun bypassable(installed: Set<String>, wanted: Nel<String>): List<String> = wanted.filter { it in installed }
