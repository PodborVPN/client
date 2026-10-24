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
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
fun networkMoves(networks: Flow<OsNetwork>, up: Flow<Boolean>): Flow<NetworkMove> =
    up.distinctUntilChanged().flatMapLatest { on ->
        if (on) movesOf(networks.filter { networkKey(it) != null }.debounce(NETWORK_SETTLE_MS).distinctUntilChangedBy(::networkKey)) else emptyFlow()
    }

private fun movesOf(settled: Flow<OsNetwork>): Flow<NetworkMove> = flow {
    var last: OsNetwork? = null
    settled.collect { now ->
        last?.let { emit(NetworkMove(it, now)) }
        last = now
    }
}
