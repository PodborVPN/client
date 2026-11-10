package podbor.linux

import arrow.core.Either
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import podbor.app.sub.inputOf
import podbor.app.sub.wordsOf
import podbor.core.Button
import podbor.remote.BrainServer
import podbor.screen.Picked
import podbor.screen.Status
import podbor.screen.SubscriptionInput
import podbor.screen.trayOf
import java.io.File
import java.net.URI

/** Состояние для `GET /api/status`: слова экрана, без ключей и адресов подписки. [problem] — чем кончился последний приём подписки. */
@Serializable
data class StatusAnswer(val session: String, val line: String, val exit: String, val problem: String)

private fun answerOf(s: Status, problem: String) = StatusAnswer(s.session.toString(), trayOf(s).line, s.exit, problem)

private fun env(name: String, default: String): String = System.getenv().getOrDefault(name, default)

/** Настройки — из окружения контейнера. */
fun configFromEnv() = LinuxConfig(
    data = File(env("PODBOR_DATA", "/data")).also { it.mkdirs() },
    assets = File(env("PODBOR_ASSETS", "/opt/podbor/assets")),
    xray = env("PODBOR_XRAY", "xray"),
    converter = env("PODBOR_CONVERTER", "libxray-cli"),
    proxyListen = env("PODBOR_PROXY_LISTEN", "0.0.0.0"),
    proxyPort = env("PODBOR_PROXY_PORT", "8898").toInt(),
)

/**
 * Запрос со своей страницы или не из браузера (нет Origin). Чужой сайт, открытый в том же браузере, достучался бы до
 * 127.0.0.1 сам: WebSocket и простой POST браузер не останавливает.
 */
private fun sameOrigin(call: ApplicationCall): Boolean {
    val origin = call.request.headers[HttpHeaders.Origin]
    if (origin == null) return true
    return Either.catch { URI(origin).authority }.getOrNull() == call.request.headers[HttpHeaders.Host]
}

/** Поднять сессию и подобрать выходы: подбор нужен, только если память сети не дала рабочего. */
private suspend fun connect(brain: Brain) {
    brain.start()
    brain.open()
    when (brain.status.value.board.button) {
        Button.Green, Button.Searching -> Unit
        Button.Idle, Button.Ready, Button.Red -> brain.press()
    }
}

/** Приём подписки: анализ, выбор, подъём прокси. Возвращает, чем кончилось, словами; пусто — всё получилось. */
private suspend fun accept(brain: Brain, input: SubscriptionInput): String =
    when (val out = brain.analyze(input, {}, {})) {
        is Either.Left -> wordsOf(out.value)
        is Either.Right -> when (val current = brain.subscriptions.current()) {
            null -> "подписка не сохранилась"
            else -> when (brain.select(current.id)) {
                is Picked.Expired -> "подписка кончилась"
                Picked.Ready -> "".also { connect(brain) }
            }
        }
    }

fun main() {
    val cfg = configFromEnv()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val brain = Brain(cfg, scope)
    val problem = MutableStateFlow("")
    val intake = Mutex()
    var running: Job? = null
    // Контейнер перезапустился — прокси поднимается сам, экран для этого не нужен.
    scope.launch { if (brain.hasSubscription()) connect(brain) }

    embeddedServer(CIO, port = env("PODBOR_WEB_PORT", "8899").toInt()) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        routing {
            get("/api/status") { call.respond(answerOf(brain.status.value, problem.value)) }
            // Экран живёт, пока открыт сокет: закрылся — его вопросы к мозгу отменяются вместе с сессией.
            webSocket("/ws") {
                if (!sameOrigin(call)) return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin"))
                val server = BrainServer(brain, this) { send(Frame.Text(it)) }
                server.start()
                for (frame in incoming) if (frame is Frame.Text) server.receive(frame.readText())
            }
            // Текст подписки или ссылка — телом запроса. Анализ идёт минуты: ответ сразу, ход — в /api/status.
            post("/api/subscription") {
                if (!sameOrigin(call)) return@post call.respond(HttpStatusCode.Forbidden)
                val input = when (val parsed = inputOf(call.receiveText())) {
                    is Either.Left -> return@post call.respondText(
                        wordsOf(parsed.value),
                        status = HttpStatusCode.BadRequest
                    )

                    is Either.Right -> parsed.value
                }
                intake.withLock {
                    listOfNotNull(running).forEach { it.cancelAndJoin() }
                    problem.value = ""
                    running = scope.launch { problem.value = accept(brain, input) }
                }
                call.respond(HttpStatusCode.Accepted, answerOf(brain.status.value, problem.value))
            }
            staticFiles("/", File(env("PODBOR_WEB", "/opt/podbor/web")))
        }
    }.start(wait = true)
}
