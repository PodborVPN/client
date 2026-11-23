package podbor.driver

import podbor.core.Reach
import podbor.core.Verdict

/** TCP-проверка неприменима к UDP-транспорту: это не успех и не отказ. */
sealed interface EndpointCheck {
    data class Tcp(val reach: Reach) : EndpointCheck
    data object NotApplicable : EndpointCheck
}

fun classify(answered: Int, check: EndpointCheck): Verdict = when (check) {
    is EndpointCheck.Tcp -> classify(answered, check.reach)
    EndpointCheck.NotApplicable -> if (answered > 0) Verdict.TelegramDc(answered) else Verdict.NoResponse
}

fun classifyInstagram(a: InstagramAnswer, check: EndpointCheck): Verdict = when (check) {
    is EndpointCheck.Tcp -> classifyInstagram(a, check.reach)
    EndpointCheck.NotApplicable -> if (a.cdnStatus == null && a.wwwStatus == null) Verdict.NoResponse else instagramVerdict(a)
}

fun classifyHttps(answered: Boolean, check: EndpointCheck): Verdict =
    if (answered) Verdict.Https(true) else failedHttps(check)

private fun failedHttps(check: EndpointCheck): Verdict = when (check) {
    EndpointCheck.NotApplicable -> Verdict.NoResponse
    is EndpointCheck.Tcp -> if (check.reach == Reach.Up) Verdict.Https(false) else Verdict.Unreachable
}

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
    Reach.Up -> instagramVerdict(a)
}

private fun instagramVerdict(a: InstagramAnswer) = Verdict.Instagram(
        cdnOk = a.cdnStatus == 200 && a.cdnBytes >= INSTAGRAM_CDN_MIN_BYTES,
        wwwOk = a.wwwStatus in setOf(200, 302, 429),
    )
