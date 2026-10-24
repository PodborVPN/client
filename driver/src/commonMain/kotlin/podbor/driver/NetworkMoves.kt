package podbor.driver

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import podbor.core.NetworkMove
import podbor.core.OsNetwork
import podbor.core.networkKey

/** Сколько сеть должна простоять без смены, чтобы переезд считался: переход Wi-Fi ↔ LTE мигает несколькими событиями. */
const val NETWORK_SETTLE_MS = 2_000L

/**
 * Переезды сети на поднятой сессии. Переезд — смена [networkKey] (Wi-Fi ↔ сотовая, смена оператора симки данных);
 * тот же ключ — не переезд. Нет сети — не сеть: ждём ключа и сравниваем с последней. Сеть, простоявшая меньше
 * [NETWORK_SETTLE_MS], не в счёт. Пока сессия не поднята, переездов нет; поднялась — отсчёт от сети на подъёме.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun networkMoves(networks: Flow<OsNetwork>, up: Flow<Boolean>): Flow<NetworkMove> =
    up.distinctUntilChanged().flatMapLatest { on -> if (on) networks.settled().moves() else emptyFlow() }

/** Устоявшиеся сети: только с ключом памяти, простоявшие [NETWORK_SETTLE_MS], подряд — разные по ключу. */
@OptIn(FlowPreview::class)
private fun Flow<OsNetwork>.settled(): Flow<OsNetwork> =
    filter { networkKey(it) != null }
        .debounce(NETWORK_SETTLE_MS)
        .distinctUntilChangedBy(::networkKey)

/** Пары соседних сетей: первая — отсчёт, каждая следующая — переезд с предыдущей. */
private fun Flow<OsNetwork>.moves(): Flow<NetworkMove> = flow {
    var last: OsNetwork? = null
    collect { now ->
        when (val from = last) {
            null -> Unit
            else -> emit(NetworkMove(from, now))
        }
        last = now
    }
}
