package podbor.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Ключ сервиса в письмах и на Ленте: tg, insta… */
@Serializable
@JvmInline
value class ServiceId(val id: String)

/**
 * По чему трафик сервиса узнаётся в маршрутизации Xray: geosite-домены, geoip-диапазоны,
 * пакеты приложений (по UID приложения-хозяина сокета). Имена — как в geosite.dat/geoip.dat.
 */
data class Match(val domains: Set<String>, val ips: Set<String>, val apps: Set<String>)

/** Какой пробой проверять выход для сервиса; исполняет её край (по виду, не по имени сервиса). */
sealed interface ProbeSpec {
    data object Telegram : ProbeSpec
    data object Instagram : ProbeSpec
}

/** label — в карточке («TG»), title — в словах экрана («Телега»), titleAcc — он же в винительном («проверяю Телегу…»). */
data class Service(
    val id: ServiceId,
    val label: String,
    val title: String,
    val titleAcc: String,
    val match: Match,
    val probe: ProbeSpec,
)

/**
 * Описание сервиса в духе Ktor: `service(id, "TG", "Телега", "Телегу", ProbeSpec.Telegram) { match { … } }`.
 * Проба — параметр, а не вызов в блоке: сервис без пробы не компилируется.
 */
fun service(
    id: ServiceId,
    label: String,
    title: String,
    titleAcc: String = title,
    probe: ProbeSpec,
    build: ServiceBuilder.() -> Unit = {},
): Service = Service(id, label, title, titleAcc, ServiceBuilder().apply(build).match, probe)

class ServiceBuilder {
    internal var match = Match(emptySet(), emptySet(), emptySet())

    fun match(build: MatchBuilder.() -> Unit) {
        val b = MatchBuilder().apply(build)
        match = Match(b.domains, b.ips, b.apps)
    }
}

class MatchBuilder {
    internal var domains = emptySet<String>()
    internal var ips = emptySet<String>()
    internal var apps = emptySet<String>()

    fun domains(vararg geosite: String) {
        domains += geosite
    }

    fun ips(vararg geoip: String) {
        ips += geoip
    }

    fun app(pkg: String) {
        apps += pkg
    }
}

val Telegram = service(ServiceId("tg"), "TG", "Телега", "Телегу", ProbeSpec.Telegram) {
    match {
        domains("telegram")
        ips("telegram")
        app("org.telegram.messenger")
    }
}

val Instagram = service(ServiceId("insta"), "IG", "Инста", "Инсту", ProbeSpec.Instagram) {
    match {
        domains("instagram", "facebook")
        // IP Meta без SNI (например, 157.240.0.60, 57.144.244.192, 57.144.245.33) доменом не узнаются.
        ips("facebook")
        app("com.instagram.android")
    }
}

/** Зашитый набор v1; позже — из конфига. Порядок объявления ни на что не влияет. */
val HARDCODED_SERVICES: Set<Service> = setOf(Telegram, Instagram)

/** Чью сеть показывает общая карточка «сеть»: сервис, за которым идёт общий выход (как в Лентах v1). */
val GENERAL_FOLLOWS: ServiceId = Telegram.id
