package podbor.core

import kotlinx.serialization.Serializable

/** Куда соединение: протокол (`tcp`/`udp`), хост (IP или домен — как его увидел Xray) и порт. */
@Serializable
data class Dest(val proto: String, val host: String, val port: Int)

/** За окно через [pair] (выход пары `pair.<сервис>.<сервер>`) открыто [count] новых соединений к [dest]. */
@Serializable
data class Conns(val pair: String, val dest: Dest, val count: Int)

/**
 * Окно журнала соединений Xray: сырые признаки — пара, назначение, счёт, — не вывод («долбит» — свёртка). Счётчик за
 * окно коммутативен: окна склеиваются в любое крупнее. [epoch] — запуск Xray, [atMs] — настенное время конца окна,
 * [monoMs] — монотонное, [windowMs] — длина окна. Только пары сервисов: остальной трафик человека сюда не попадает.
 */
@Serializable
data class ConnSample(val epoch: Long, val atMs: Long, val monoMs: Long, val windowMs: Long, val conns: List<Conns>)

/**
 * Порог «долбит»: новых соединений к одному адресу через пару за окно. Замер на телефоне: здоровый разгон Телеги —
 * до 7 к адресу, долбёжка через «не тянущий» сервер — 35–45 за 10 с.
 */
const val HAMMER_CONNS = 20

/** Сервис через свою пару: спокоен или долбит [dest] — [count] новых соединений за [windowMs]. */
sealed interface Hammer {
    data object Calm : Hammer
    data class Hammering(val dest: Dest, val count: Int, val windowMs: Long) : Hammer
}

/**
 * Долбит ли сервис через [pair] сейчас ([nowMonoMs]): окна не старше двух своих длин, только эта пара — долбило через
 * прежний выход, новый не виноват. Из свежих — последнее окно с адресом от [HAMMER_CONNS] соединений, его самый частый.
 */
fun hammerOf(samples: List<ConnSample>, pair: String, nowMonoMs: Long): Hammer {
    val loud = samples
        .filter { nowMonoMs - it.monoMs <= 2 * it.windowMs }
        .mapNotNull { s ->
            when (val c = s.conns.filter { it.pair == pair && it.count >= HAMMER_CONNS }.maxByOrNull { it.count }) {
                null -> null
                else -> Hammer.Hammering(c.dest, c.count, s.windowMs)
            }
        }
    return when (val last = loud.lastOrNull()) {
        null -> Hammer.Calm
        else -> last
    }
}

/** Слова на карточке: сколько раз за сколько секунд и вывод — проба прошла, а приложение через этот сервер не держится. */
fun hammerWords(service: Service, h: Hammer.Hammering): String =
    "${service.title} ${h.count} раз за ${h.windowMs / 1000} с переподключается к одному адресу — через этот сервер не тянет"
