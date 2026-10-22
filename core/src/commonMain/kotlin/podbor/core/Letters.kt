@file:UseSerializers(NonEmptyListSerializer::class)
@file:OptIn(ExperimentalSerializationApi::class)

package podbor.core

import arrow.core.serialization.NonEmptyListSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/** Буква — всё, что входит в ядро. Время и ответы мира приходят только так. */
@Serializable
sealed interface Letter {
    val atMs: Long

    /** Экран открыт: сеть от ОС и состояние VPN. Детектор при открытии — Open.kt. */
    @Serializable
    data class Opened(override val atMs: Long, val os: OsNetwork, val vpn: Vpn) : Letter

    /**
     * Нажатие кнопки. Память (успехи, блоки) приходит здесь — ядро ничего не хранит между нажатиями.
     * prior — раунды линии, снятые детектором при открытии: свежие заменяют замер в нажатии.
     * service — чей это подбор (ключ памяти и вид пробы); pinned — балансер сервиса закреплён за currentExit,
     * иначе найденный currentExit всё равно надо выдать (MakeExit). Умолчания — Лента v1: трубопровод Телеги.
     */
    @Serializable
    data class Pressed(
        override val atMs: Long,
        val os: OsNetwork,
        val servers: List<Server>,
        val currentExit: ServerId?,
        val memory: Memory,
        val prior: Prior = Prior.None,
        val service: ServiceId = Telegram.id,
        val pinned: Boolean = true,
        /** Подсказки паспорта подписки по `Server.key`; пусто — как раньше. */
        val hints: Map<String, WhiteHint> = emptyMap(),
    ) : Letter

    @Serializable
    data class LineObserved(override val atMs: Long, val line: LineObservation) : Letter

    /** service — чья проба; заполняет только анализ (у трубопровода сервис известен из прогона, Ленты v1/v2 — без него, умолчание не пишется). */
    @Serializable
    data class ProbeAnswered(
        override val atMs: Long,
        val server: ServerId,
        val verdict: Verdict,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val service: ServiceId = Telegram.id,
    ) : Letter

    /**
     * Анализ подписки: проверить [servers] по набору [services] и узнать, где у каждого выход.
     * memory — та же память сети, что у нажатия: блоки IP:порт общие, успехи анализа уходят в неё делом Remember.
     */
    @Serializable
    data class Scan(
        override val atMs: Long,
        val os: OsNetwork,
        val servers: List<Server>,
        val services: List<ServiceId>,
        val hints: Map<String, WhiteHint>,
        val prior: Prior = Prior.None,
        val memory: Memory = Memory.Empty,
        /** Ключи серверов, чей SNI в белом списке SNI: факт карточки и паспорта, на [hints] не влияет. */
        val sniWhite: Set<String> = emptySet(),
        /**
         * Успех пробы засчитывается только вторым успехом той же пробы (один выстрел завышает успех). В
         * букве, а не в коде: Ленты до правила (поля нет — false) проигрываются по-старому.
         */
        val confirmFits: Boolean = false,
    ) : Letter

    /** Ответ на MeasureExit: выход сервера по эталону; Unknown — через сервер эталон не ответил. */
    @Serializable
    data class ExitSeen(override val atMs: Long, val server: ServerId, val exit: Exit) : Letter
}

/** VPN в момент открытия: выключен, включён на сервере из подписки или на выходе, которого в ней нет. */
@Serializable
sealed interface Vpn {
    @Serializable data object Off : Vpn
    @Serializable data class On(val exit: Server) : Vpn
    /** Выход не из подписки (другой конфиг, подписка сменилась): пробовать нечем, name и транспорт неизвестны. */
    @Serializable data class Unlisted(val tag: ServerId) : Vpn
}

/** Что детектор при открытии успел узнать о линии до нажатия. */
@Serializable
sealed interface Prior {
    @Serializable data object None : Prior
    @Serializable data class Detected(val rounds: Nel<Letter.LineObserved>) : Prior
}

/** Дело — всё, что ядро велит краю. */
@Serializable
sealed interface Deed {
    @Serializable data object ProbeLine : Deed
    /**
     * Проба сервиса через кандидата; имя на проводе прежнее — Ленты v1 читаются.
     * service заполняет только анализ: нажатие и трубопроводы оставляют умолчание, иначе Ленты v2 разойдутся при replay;
     * умолчание на провод не пишется — строки Лент нажатия байт в байт прежние.
     */
    @Serializable @SerialName("podbor.core.Deed.ProbeTelegram") data class Probe(
        val server: ServerId,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val service: ServiceId = Telegram.id,
    ) : Deed
    @Serializable data class MakeExit(val server: ServerId) : Deed
    @Serializable data class MeasureSpeed(val server: ServerId) : Deed
    /** Сохранить память нажатия (успех, блоки IP:порт). */
    @Serializable data class Remember(val memory: Memory) : Deed
    /** Узнать выход сервера: запрос к своему эталону через сервер, страна — по локальному geoip. */
    @Serializable data class MeasureExit(val server: ServerId) : Deed
    @Serializable data class SavePassport(val passport: Passport) : Deed
}
