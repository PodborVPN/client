@file:OptIn(ExperimentalWasmJsInterop::class)

package podbor.web

/** Браузерный WebSocket: только то, чем пользуется экран. */
external class WebSocket(url: String) : JsAny {
    fun send(text: String)
    var onopen: ((JsAny) -> Unit)?
    var onmessage: ((SocketMessage) -> Unit)?
    var onclose: ((JsAny) -> Unit)?
}

external interface SocketMessage : JsAny {
    val data: JsString
}

/** Адрес сокета — на том же хосте, откуда пришла страница. */
@JsFun("() => (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws'")
external fun socketUrl(): String

@JsFun("() => location.reload()")
external fun reloadPage()

@JsFun("(url) => window.open(url, '_blank', 'noopener')")
external fun openInNewTab(url: String)
