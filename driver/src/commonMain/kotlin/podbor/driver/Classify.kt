package podbor.driver

import podbor.core.Reach
import podbor.core.Verdict

/**
 * Подпись провала по замеру: через SOCKS Xray TCP до сервера не виден,
 * поэтому край снимает его сам. TCP ✗ — Unreachable; TCP ✓ и ни одного DC — TlsSilence; иначе — сколько DC ответили.
 */
fun classify(answered: Int, tcp: Reach): Verdict = when (tcp) {
    Reach.Down -> Verdict.Unreachable
    Reach.Up -> if (answered == 0) Verdict.TlsSilence else Verdict.TelegramDc(answered)
}

const val INSTAGRAM_CDN_URL = "https://static.cdninstagram.com/rsrc.php/v4/yC/r/gbg9zUHdHQP.js"

/** Заморозка режет поток на ~16 КБ: целым считаем файл не меньше 96 КБ. */
const val INSTAGRAM_CDN_MIN_BYTES = 96 * 1024
const val INSTAGRAM_WWW_URL = "https://www.instagram.com/"

/**
 * Инста: TCP ✗ — Unreachable; иначе по двум частям. Никогда не TlsSilence: блок IP:порт ставит только Телега.
 * www «ответил сам» — 200/302/429 (429 — лимит самого Инстаграма, не блок).
 */
fun classifyInstagram(a: InstagramAnswer, tcp: Reach): Verdict = when (tcp) {
    Reach.Down -> Verdict.Unreachable
    Reach.Up -> Verdict.Instagram(
        cdnOk = a.cdnStatus == 200 && a.cdnBytes >= INSTAGRAM_CDN_MIN_BYTES,
        wwwOk = a.wwwStatus in setOf(200, 302, 429),
    )
}
