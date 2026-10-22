package podbor.core

import arrow.core.nonEmptyListOf

/** Сервер для тестов: адрес по умолчанию — 10.0.0.<число из тега>. */
fun server(
    tag: String,
    entry: Entry,
    host: String = "10.0.0.${tag.filter(Char::isDigit).toIntOrNull() ?: 1}",
    port: Int = 443,
    transport: Transport = Transport.Grpc,
    name: String = tag,
): Server = Server(
    id = ServerId(tag),
    name = name,
    endpoint = Endpoint(host, port),
    transport = transport,
    fingerprint = "chrome",
    entry = entry,
    key = "key-$tag",
)

/**
 * Раунд линии для тестов: Up — три рукопожатия, Down — три SYN без ответа (1,5 с ≥ 10×RTT опоры).
 * Один раунд Up/Down — это только подозрение; белые списки — два таких раунда подряд.
 */
fun line(russia: Reach, abroad: Reach): LineObservation {
    fun side(prefix: String, r: Reach) = nonEmptyListOf(0, 1, 2).map { i ->
        Sample("$prefix$i", if (r == Reach.Up) Handshake.Ok(connectMs = 30) else Handshake.Silent(Stage.Connect, waitedMs = 1_500))
    }
    return LineObservation(side("ru", russia), side("far", abroad))
}
