@file:OptIn(ExperimentalWasmJsInterop::class)

package podbor.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import podbor.remote.RemotePort
import podbor.screen.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Пауза перед перезагрузкой страницы, когда демон пропал: перезапуск контейнера — несколько секунд. */
private const val RECONNECT_MS = 2_000L

/** Обновлений в браузере нет: образ обновляют снаружи. */
private object NoUpdates : UpdateMemory {
    override fun known(): Release? = null
    override fun remember(release: Release) = Unit
}

/** Как включается VPN на Linux: демон уже подключён, разрешений система не спрашивает. */
private class DaemonSession(private val vm: MainViewModel, private val port: RemotePort) : SessionPlatform {
    override fun consentNeeded() = false
    override fun askConsent() = Unit
    override fun startEngine() = port.start()
    override fun followEngine() = Unit
    override fun stopEngine() = port.stop()
    override fun reopen() = vm.opened(askPermissions = false)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = MainScope()
    val socket = WebSocket(socketUrl())
    val opened = CompletableDeferred<Unit>()
    val port = RemotePort(scope) { text ->
        opened.await()
        socket.send(text)
    }
    socket.onopen = { opened.complete(Unit) }
    socket.onmessage = { port.receive(it.data.toString()) }
    socket.onclose = {
        scope.launch {
            delay(RECONNECT_MS.milliseconds)
            reloadPage()
        }
    }
    scope.launch {
        port.awaitReady()
        val vm = MainViewModel(
            version = "linux",
            selfDistributed = false,
            memory = NoUpdates,
            nowMs = { Clock.System.now().toEpochMilliseconds() },
            saved = SavedStateHandle(),
            shelf = { port.shelf() },
        )
        val story = SessionStory(vm, DaemonSession(vm, port))
        vm.attach(port)
        vm.opened(askPermissions = false)
        scope.launch { vm.needsStart.collect { if (it) story.send(SessionLetter.StartDue) } }
        ComposeViewport("podbor") { WebApp(vm, port, story, scope) }
    }
}
