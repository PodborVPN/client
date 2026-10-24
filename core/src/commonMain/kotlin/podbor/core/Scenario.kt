package podbor.core

import kotlinx.serialization.Serializable

/** Тип сети по ОС — только априори: правду о режиме говорит линия. */
@Serializable
sealed interface OsNetwork {
    /** [tower] — вышка и сигнал; не участвует в ключах памяти и поведении нажатия, только показ и Лента. */
    @Serializable data class Cellular(val operator: String, val mccMnc: String, val tower: Tower = Tower.Unknown) : OsNetwork
    @Serializable data object Wifi : OsNetwork
    @Serializable data object None : OsNetwork
}

/** Сеть сменилась по ключу памяти ([networkKey]): выход, подобранный на [from], на [to] проверяется заново. */
data class NetworkMove(val from: OsNetwork, val to: OsNetwork)

/** Где подобран выход: на сети [Network.os] или неизвестно (Ленты до поля, выход ставили не мы). */
@Serializable
sealed interface ChosenOn {
    @Serializable data object Unknown : ChosenOn
    @Serializable data class Network(val os: OsNetwork) : ChosenOn
}

/** Выход и нынешняя сеть: где подобран — неизвестно, на этой же сети или сеть с тех пор сменилась. */
sealed interface Relocation {
    data object Unknown : Relocation
    data object Stayed : Relocation
    data class Moved(val move: NetworkMove) : Relocation
}

/** Переезд — обе сети с ключом памяти и ключи разные; у какой-то нет ключа (нет сети) — сравнивать не с чем. */
fun relocationOf(chosenOn: ChosenOn, now: OsNetwork): Relocation = when (chosenOn) {
    ChosenOn.Unknown -> Relocation.Unknown
    is ChosenOn.Network -> {
        val from = networkKey(chosenOn.os)
        val to = networkKey(now)
        when {
            from == null || to == null -> Relocation.Unknown
            from == to -> Relocation.Stayed
            else -> Relocation.Moved(NetworkMove(chosenOn.os, now))
        }
    }
}

/** Достижимость сервера кандидата по TCP (край, TcpReach). */
@Serializable
enum class Reach { Up, Down }

/** Сценарий, который видит человек; внутри каждого — своя машина перебора. */
@Serializable
sealed interface Scenario {
    @Serializable data object MobileOpen : Scenario
    @Serializable data object MobileWhitelist : Scenario
    /** Один раунд «похоже на белые списки», подтверждения нет (ещё нет или второй раунд не прояснил). */
    @Serializable data object SuspectWhitelist : Scenario
    @Serializable data object Wifi : Scenario
    @Serializable data object Offline : Scenario
    @Serializable data object Unclear : Scenario
}

/** Вышка, к которой приписан телефон: ОС может её не отдать, поэтому сумма, а не nullable-поля. */
@Serializable
sealed interface Tower {
    @Serializable data object Unknown : Tower

    /** [cellId] — только в Ленте; на экране EARFCN и RSRP. */
    @Serializable data class Known(val cellId: Long, val earfcn: Int, val rsrpDbm: Int) : Tower
}
