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
