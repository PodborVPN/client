package podbor.screen

import arrow.core.Either
import kotlin.io.encoding.Base64

/**
 * Что из подписки не стало серверами: конвертер молча выбрасывает то, чего не понял, поэтому ссылки считаем сами.
 * [servers] — серверы конвертера; [unsupported] — ссылки схем, которых Podbor не берёт, по схеме; [broken] — ссылки
 * своих схем, из которых сервер не вышел.
 */
data class Skipped(val servers: Int, val unsupported: Map<String, Int>, val broken: Int) {
    /** Есть что сказать: чужие или битые ссылки. */
    val any: Boolean get() = unsupported.isNotEmpty() || broken > 0

    /** В тексте нашлась хоть одна ссылка; нет — это не список ссылок (Clash YAML, JSON). */
    val recognized: Boolean get() = servers > 0 || any
}

/** Схемы, из которых конвертер и ядро делают серверы. */
private val OURS = setOf("vless", "vmess", "trojan", "ss")

/** Другие имена той же схемы. */
private val ALIASES = mapOf("hy2" to "hysteria2")

private val SCHEME = Regex("""^([a-z][a-z0-9+.-]*)://""", RegexOption.IGNORE_CASE)

/** Ссылки [text] по схемам против [servers] конвертера; текст секретен — наружу только схемы и счёт. */
fun skippedOf(text: String, servers: Int): Skipped {
    val schemes = linesOf(text)
        .mapNotNull { SCHEME.find(it.trim()) }
        .map { m -> m.groupValues[1].lowercase().let { ALIASES.getOrElse(it) { it } } }
    val (ours, foreign) = schemes.partition { it in OURS }
    return Skipped(servers, foreign.groupingBy { it }.eachCount(), (ours.size - servers).coerceAtLeast(0))
}

/** Строки подписки; текст без ссылок, но целиком base64 (так отдают провайдеры), — раскрытый. */
private fun linesOf(text: String): List<String> {
    if ("://" in text) return text.lines()
    val packed = text.filterNot(Char::isWhitespace)
    // Первый раскрывшийся вариант base64; ни один — текст как есть.
    val opened = sequenceOf(Base64.Default, Base64.UrlSafe)
        .mapNotNull { b -> Either.catch { b.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(packed).decodeToString() }.getOrNull() }
    return (opened + text).first().lines()
}
