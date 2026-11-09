package podbor.core

import kotlinx.serialization.Serializable

/** Исключённые страны конечного выхода. Без ограничений неизвестный выход допустим. */
@Serializable
data class ExitFilter(val excluded: Set<String> = emptySet()) {
    fun allows(exit: Exit): Boolean = excluded.isEmpty() || when (exit) {
        Exit.Unknown -> false
        is Exit.At -> knownCountry(exit.country) && exit.country.uppercase() !in excluded
    }
}

/** Двухбуквенный код страны; пустое значение и «?» не являются страной. */
fun knownCountry(country: String): Boolean = country.length == 2 && country.all { it in 'A'..'Z' }
