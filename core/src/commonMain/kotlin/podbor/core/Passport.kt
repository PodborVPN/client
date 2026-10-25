package podbor.core

import kotlinx.serialization.Serializable

/** Подсказка по спискам белых: IP входа сервера — в списке разрешённых в «белом» режиме. */
@Serializable
sealed interface WhiteHint {
    @Serializable data object Likely : WhiteHint
    @Serializable data object Unlikely : WhiteHint
}

/** Выход сервера: IP, с которого он ходит в сеть (по эталону), и страна по локальному geoip. */
@Serializable
sealed interface Exit {
    @Serializable data class At(val ip: String, val country: String) : Exit
    @Serializable data object Unknown : Exit
}

/** Откликнулся ли сервер на разборе подписки. */
@Serializable
sealed interface Seen {
    @Serializable data class Answered(val atMs: Long) : Seen
    @Serializable data object Silent : Seen
}

@Serializable
data class ServerFacts(
    val transport: Transport,
    val entry: Entry,
    val whiteHint: WhiteHint,
    val exit: Exit,
    val seen: Seen,
    /** SNI сервера — в белом списке SNI; отдельно от [whiteHint]. Паспорта до этого поля читаются как false. */
    val sniWhite: Boolean = false,
)

/** Паспорт подписки: факты по серверам, ключ — Server.key. */
@Serializable
data class Passport(val servers: Map<String, ServerFacts>, val analyzedAtMs: Long)

/** Ключи newer заменяют, прочие остаются; время — newer. */
fun Passport.merged(newer: Passport): Passport = Passport(servers + newer.servers, newer.analyzedAtMs)

/** Факты только серверов [keys] (забвение удалённой подписки); время — прежнее. */
fun Passport.keptOnly(keys: Set<String>): Passport = copy(servers = servers.filterKeys { it in keys })
