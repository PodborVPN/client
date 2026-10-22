package podbor.core

import kotlinx.serialization.Serializable

/** Вердикт пробы сервиса через кандидата — сумма по подписи провала. */
@Serializable
sealed interface Verdict {
    /** TCP до сервера не установился. */
    @Serializable data object Unreachable : Verdict
    /** TCP есть, рукопожатие без ответа — подпись блока IP:порт. */
    @Serializable data object TlsSilence : Verdict
    /** Рукопожатие прошло; ok — сколько DC Telegram ответили resPQ. */
    @Serializable data class TelegramDc(val ok: Int) : Verdict
    /** Рукопожатие прошло; cdnOk/wwwOk — ответили ли CDN и www Инстаграма. */
    @Serializable data class Instagram(val cdnOk: Boolean, val wwwOk: Boolean) : Verdict
}

@Serializable
enum class VerdictKind { Unreachable, TlsSilence, ServiceDead, Fits }

/** Fits ⟺ ответили ≥ 2 DC: отказ одного DC у живого сервера бывает (СС, DC5). */
fun Verdict.kind(): VerdictKind = when (this) {
    Verdict.Unreachable -> VerdictKind.Unreachable
    Verdict.TlsSilence -> VerdictKind.TlsSilence
    is Verdict.TelegramDc -> if (ok >= 2) VerdictKind.Fits else VerdictKind.ServiceDead
    is Verdict.Instagram -> if (cdnOk && wwwOk) VerdictKind.Fits else VerdictKind.ServiceDead
}
