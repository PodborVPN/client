package podbor.driver

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.security.SecureRandom

/**
 * Логин и пароль SOCKS-входов Xray на устройстве: без них приложение-сосед по телефону не пройдёт через серверы
 * человека и не узнает, куда они выходят. Случайные на процесс: снаружи их не узнать, и хранить незачем.
 */
data class SocksAuth(val user: String, val pass: String) {
    companion object {
        fun random(): SocksAuth {
            val rnd = SecureRandom()
            fun hex() = ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
            return SocksAuth(hex(), hex())
        }
    }
}

/** Пароль процесса: его ставит конфиг Xray и предъявляют все клиенты SOCKS; первое обращение ставит [installSocksAuth]. */
val LOCAL_SOCKS: SocksAuth by lazy { SocksAuth.random().also(::installSocksAuth) }

/**
 * Пароль [auth] — клиентам SOCKS через `Proxy` платформы (проба Инсты и выхода, скачивание туннелем): и JDK, и libcore
 * Android (ojluni `SocksSocketImpl.authenticate`) спрашивают его у `Authenticator`. Отдаём только SOCKS5 на loopback —
 * чужим прокси и HTTP-авторизации ничего.
 */
fun installSocksAuth(auth: SocksAuth) {
    Authenticator.setDefault(object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? =
            if (requestingProtocol == "SOCKS5" && requestingSite?.isLoopbackAddress == true) {
                PasswordAuthentication(auth.user, auth.pass.toCharArray())
            } else null
    })
}
