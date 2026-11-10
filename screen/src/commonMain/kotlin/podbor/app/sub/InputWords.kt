package podbor.app.sub

import podbor.core.plural
import podbor.screen.Announce
import podbor.screen.InputError
import podbor.screen.Skipped

fun wordsOf(e: InputError): String = when (e) {
    is InputError.Download ->
        if (e.whitelist) "не могу скачать подписку: похоже, белые списки. Попроси прислать её текстом или QR в VK или Max — или добавь через Wi-Fi"
        else "не могу скачать подписку: сеть не отвечает"
    is InputError.NoServers -> if (e.skipped.recognized) "не нашёл поддерживаемых серверов" else "не узнал формат подписки"
    is InputError.DeviceLimit -> limitWords(e.announce)
    InputError.ScanFailed -> "не смог проверить подписку — попробуй ещё раз"
    InputError.Empty, InputError.Unreadable -> "в буфере нет подписки"
    InputError.HappOnly ->
        "это зашифрованная ссылка только для приложения Happ — попроси у провайдера обычную ссылку подписки (https://…)"
}

/** Отказ по лимиту устройств: словами провайдера, если он их дал. */
private fun limitWords(announce: Announce): String = when (announce) {
    is Announce.Text -> "лимит устройств подписки: ${announce.text}"
    Announce.None -> "лимит устройств подписки исчерпан — отвяжи старое устройство у провайдера"
}

/** Слова шторки о ссылках, не ставших серверами: заголовок, счёт по схемам, что делать. */
data class SkippedWords(val title: String, val lines: List<String>, val note: String)

private const val OURS_WORDS = "vless, vmess, trojan и shadowsocks"

/** Строка счёта, если есть что считать. */
private fun lineWhen(n: Int, line: (Int) -> String): List<String> = if (n > 0) listOf(line(n)) else emptyList()

/** Схемы — по убыванию счёта (равные — по имени), битые — последней строкой. */
fun skippedWords(s: Skipped): SkippedWords {
    val lines = s.unsupported.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { (scheme, n) -> "$scheme — $n" } +
        lineWhen(s.broken) { "${plural(it, "битая ссылка", "битые ссылки", "битых ссылок")} — $it" } +
        lineWhen(s.repeats) { "повторы того же сервера — $it" }
    val total = s.servers + s.broken + s.repeats + s.unsupported.values.sum()
    return when {
        s.servers > 0 -> SkippedWords(
            "Проверю ${s.servers} ${plural(s.servers, "сервер", "сервера", "серверов")} из $total",
            lines,
            "остальные Podbor не поддерживает — пропущу",
        )
        s.recognized -> SkippedWords(
            "Podbor не поддерживает эту подписку",
            lines,
            "Podbor понимает $OURS_WORDS — попроси у провайдера подписку на них",
        )
        else -> SkippedWords(
            "Не узнал формат подписки",
            lines,
            "Podbor понимает ссылки $OURS_WORDS, а это похоже на настройки Clash или sing-box — попроси у провайдера подписку для v2rayNG",
        )
    }
}
