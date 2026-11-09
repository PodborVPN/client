package podbor.driver

import podbor.core.Exit
import podbor.core.knownCountry

/** Основной эталон с одним повтором; запасной независимо возвращает и IP, и его страну. */
class ExitDiscovery(
    private val primary: ExitProbe,
    private val countryOf: suspend (String) -> String?,
    private val fallback: suspend (Int) -> Exit,
) {
    suspend fun measure(port: Int): Exit {
        val first = primary.exitIp(port)
        val ip = when (first) {
            null -> primary.exitIp(port)
            else -> first
        }
        val local = when (ip) {
            null -> Exit.Unknown
            else -> Exit.At(ip, country(ip))
        }
        if (hasCountry(local)) return local
        val backup = fallback(port)
        return if (hasCountry(backup)) backup else local
    }

    private suspend fun country(ip: String): String = when (val found = countryOf(ip)) {
        null -> "?"
        else -> found
    }
}

private fun hasCountry(exit: Exit): Boolean = when (exit) {
    Exit.Unknown -> false
    is Exit.At -> knownCountry(exit.country)
}
