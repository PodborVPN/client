package podbor.ioskit

import podbor.driver.sha256Hex

/** Точка входа Swift в общий код: отпечаток пустого SHA-256 через ядро края — связка Kotlin ↔ iOS живая. */
fun kitCheck(): String = sha256Hex(ByteArray(0)).take(8)
