package podbor.driver

import arrow.core.nonEmptyListOf
import org.kotlincrypto.hash.sha2.SHA256
import podbor.core.Handshake
import podbor.core.Nel
import podbor.core.Role

/** Чему верить в сертификате эталона: системному хранилищу или одному зашитому отпечатку (самоподписанный podbor-ref). */
sealed interface Trust {
    data object System : Trust
    data class Pinned(val sha256: String) : Trust
}

/**
 * Эталон линии. label — публичное имя в Ленте; host — куда идём, sni — что просим в ClientHello.
 * role — чей эталон: Own (наш VPS) закрытым запрещает раунду «открыто» (roundOf).
 */
data class Reference(val label: String, val host: String, val port: Int, val sni: String, val trust: Trust, val role: Role = Role.Public)

private fun russian(host: String) = Reference(host, host, 443, host, Trust.System)

/**
 * Российский эталон по IP — DNS ему не нужен: имена не резолвятся, а он отвечает — значит, сломан DNS сети, а не связь
 * ([podbor.core.dnsOf]). Сертификат Яндекса выписан и на IP, и на имя DoT.
 */
private val YANDEX_DNS = Reference("dns.yandex", "77.88.8.8", 443, "common.dot.dns.yandex.net", Trust.System)

val RUSSIA_REFS: Nel<Reference> = nonEmptyListOf(russian("ya.ru"), russian("vk.com"), russian("gosuslugi.ru"), YANDEX_DNS)

/** Свой эталон на VPS: самоподписанный сертификат под отпечатком; он же отвечает IP запроса (проба выхода). */
internal val PODBOR_REF = Reference("podbor-ref", "195.133.26.5", 443, "podbor-ref", Trust.Pinned("4F2E4982250AA54D47926DBCD5D63FB8A59EA38B1FF2FF5D0C539F1B65EDD165"), Role.Own)

val ABROAD_REFS: Nel<Reference> = nonEmptyListOf(
    Reference("one.one.one.one", "1.1.1.1", 443, "one.one.one.one", Trust.System),
    Reference("dns.google", "8.8.8.8", 443, "dns.google", Trust.System),
    PODBOR_REF,
)

/** Одно рукопожатие с эталоном. Любой исход — значение; бросать можно только отмену. */
fun interface Handshaker {
    suspend fun shake(ref: Reference): Handshake
}

fun sha256Hex(der: ByteArray): String =
    SHA256().digest(der).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Отпечаток сошёлся: регистр и двоеточия в ожидаемом не важны. */
fun pinMatches(expectedSha256Hex: String, der: ByteArray): Boolean =
    expectedSha256Hex.filter { it != ':' }.equals(sha256Hex(der), ignoreCase = true)
