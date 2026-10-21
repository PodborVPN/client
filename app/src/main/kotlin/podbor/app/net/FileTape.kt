package podbor.app.net

import podbor.driver.TapeSink
import java.io.File

/**
 * Лента в файл JSONL: строки Ленты v1 (Row, driver/Tape.kt) как есть.
 * Одна на сервис: замер линии до подъёма и сессия пишут в неё из разных корутин — строка дописывается целиком.
 */
class FileTape(private val file: File) : TapeSink {
    @Synchronized
    override fun write(line: String) {
        file.appendText(line + "\n")
    }
}
