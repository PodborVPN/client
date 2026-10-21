package podbor.core

/** «1 сервер», «2 сервера», «5 серверов». */
fun serversCount(n: Int): String = "$n " + plural(n, "сервер", "сервера", "серверов")
