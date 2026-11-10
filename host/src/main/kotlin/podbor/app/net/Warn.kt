package podbor.app.net

/** Предупреждение края без логгера платформы: только вид сбоя, без содержимого. */
fun warnToStderr(message: String) {
    System.err.println("Podbor: $message")
}
