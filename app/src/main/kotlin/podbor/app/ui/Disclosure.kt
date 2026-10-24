package podbor.app.ui

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable

/**
 * Разрешения после шторки: геолокацию — только с согласия (вышку Android отдаёт лишь с ней); уведомление «Podbor ·
 * выход» — на Android 13+ всегда, объяснения ему не нужно.
 */
fun permissionsFor(locationAllowed: Boolean, sdk: Int): List<String> =
    (if (locationAllowed) listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList()) +
        if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

/**
 * Шторка до системного запроса геолокации: что берём, зачем, чего не берём и куда уходит. «Разрешить» — акцентом,
 * «Не сейчас» — тише; смахнуть — тоже «не сейчас».
 */
@Composable
fun LocationSheet(onAllow: () -> Unit, onDecline: () -> Unit) {
    ChoiceSheet("Зачем Podbor геолокация", yes = "Разрешить", no = "Не сейчас", onYes = onAllow, onNo = onDecline) {
        Para("Номер вышки сотовой связи и её сигнал Android отдаёт только приложениям с доступом к геолокации.")
        Para("Podbor берёт только вышку и сигнал: они видны в строке «Сеть» и помогают понять, где именно не работает. GPS и координаты Podbor не читает.")
        Para("Всё остаётся на телефоне и никуда не отправляется. В Ленту вышка попадает под псевдонимом — и только когда ты сам нажмёшь «поделиться Лентой».")
        Para("Без доступа всё работает, просто без вышки.", Muted)
    }
}

/**
 * Шторка ремонта DNS — по 🔧 на плашке «Сеть», только действием человека. Не починено: что сломано, что сделает Podbor и
 * что это значит — «Починить» / «Не сейчас». Починено на этой сети: как обошли — «Вернуть DNS сети» / «Оставить».
 */
@Composable
fun DnsSheet(fixed: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    if (fixed) {
        ChoiceSheet("Российские сайты — через Яндекс DNS", yes = "Вернуть DNS сети", no = "Оставить", onYes = onYes, onNo = onNo) {
            Para("На этой сети Podbor узнаёт адреса российских сайтов через Яндекс DNS (77.88.8.8), а не через DNS сети.")
            Para("Вернуть — если DNS сети снова работает. VPN переподключится на пару секунд.", Muted)
        }
    } else {
        ChoiceSheet("DNS сети не отвечает", yes = "Починить", no = "Не сейчас", onYes = onYes, onNo = onNo) {
            Para("Адреса сайтов по именам эта сеть не отдаёт, а связь есть. Из-за этого российские сайты не открываются даже с VPN.")
            Para("Podbor может узнавать адреса российских сайтов через Яндекс DNS (77.88.8.8) — только на этой сети.")
            Para("VPN переподключится на пару секунд. Вернуть можно здесь же.", Muted)
        }
    }
}

/**
 * Шторка «постоянный VPN»: зачем, что включить в настройках Android и чем это обходится (с блокировкой без Podbor
 * интернета нет). «Открыть настройки» — акцентом, «Не сейчас» — тише.
 */
@Composable
fun AlwaysOnSheet(onOpen: () -> Unit, onLater: () -> Unit) {
    ChoiceSheet("Чтобы VPN не выключался", yes = "Открыть настройки", no = "Не сейчас", onYes = onOpen, onNo = onLater) {
        Para("Android умеет сам держать VPN включённым — после перезагрузки и когда система выгружает приложения — и не пускать трафик мимо него.")
        Para("В настройках VPN нажми шестерёнку у Podbor и включи «Постоянная VPN» и «Блокировать соединения без VPN».")
        Para("Учти: с блокировкой, пока Podbor выключен, интернета не будет совсем. Выключать тогда — в тех же настройках.", Muted)
    }
}
