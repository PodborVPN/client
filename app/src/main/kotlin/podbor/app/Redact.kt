package podbor.app

/** Тексты ошибок Xray/gRPC могут нести адреса и имена — маскируем всё похожее на хост, IP или UUID. */
private val SENSITIVE = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}|\b\d{1,3}(\.\d{1,3}){3}\b|\b[\w-]+(\.[\w-]+)+\b|\[[0-9a-fA-F:]+]""")

fun redact(s: String): String = SENSITIVE.replace(s, "…")
