package podbor.driver

import java.security.MessageDigest

/**
 * Проба Телеги — статичный блоб, как фейки zapret2:
 * intermediate-транспорт + незашифрованный req_pq_multi с фиксированным nonce. Telegram msg_id в нём не проверяет.
 */
val TG_BLOB: ByteArray = loadBlob()

private const val TG_BLOB_SHA256 = "b9b1cba347934cccabbca53bbc47c4c05e0167668f9af2f4228f4f05100e995b"
private const val RES_PQ = 0x05162463

private fun loadBlob(): ByteArray {
    val bytes = object {}.javaClass.getResourceAsStream("/tg_req_pq_multi.bin")!!.use { it.readBytes() }
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    check(sha == TG_BLOB_SHA256) { "блоб Телеги подменён: $sha" }
    return bytes
}

/** Nonce блоба: байты 24..39 (после заголовка транспорта 8 и auth_key_id/msg_id/length 20 и конструктора 4). */
private val NONCE = TG_BLOB.copyOfRange(TG_BLOB.size - 16, TG_BLOB.size)

/** DC1–DC5, :443. DC5 бывает молчит у живого сервера — потому «прошла» = ≥ 2 DC. */
val TELEGRAM_DCS: List<Pair<String, Int>> = listOf(
    "149.154.175.50" to 443, "149.154.167.51" to 443, "149.154.175.100" to 443,
    "149.154.167.91" to 443, "91.108.56.130" to 443,
)

/**
 * DC, которые опрашивает проба: три — столько же соединений к IP:порт кандидата, сколько разрешает бюджет.
 * «Прошла» = ≥ 2 из 3 (Verdict.TelegramDc(ok >= 2) в ядре).
 */
val PROBE_DCS: List<Pair<String, Int>> = TELEGRAM_DCS.take(3)

enum class DcAnswer { ResPq, Garbage, Silent }

/** Чистая: кадр ответа без 4-байтной длины → суд. ResPq = конструктор resPQ и эхо нашего nonce. */
fun judgeResPq(frame: ByteArray?): DcAnswer = when {
    frame == null -> DcAnswer.Silent
    frame.size < 40 -> DcAnswer.Garbage
    le32(frame, 20) == RES_PQ && frame.copyOfRange(24, 40).contentEquals(NONCE) -> DcAnswer.ResPq
    else -> DcAnswer.Garbage
}

internal fun le32(b: ByteArray, at: Int): Int =
    (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
        ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
