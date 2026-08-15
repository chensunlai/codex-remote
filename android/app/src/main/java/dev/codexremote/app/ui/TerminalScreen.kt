package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.RuntimeState
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(state: AppState, viewModel: MainViewModel) {
    val serviceId = state.selectedServiceId
    val service = state.services.firstOrNull { it.id == serviceId }

    if (serviceId != null && service?.runtimeState == RuntimeState.CONNECTED) {
        key(serviceId) {
            val controller = remember(serviceId) {
                RemoteTerminalController(viewModel, service.home)
            }
            DisposableEffect(controller) {
                onDispose(controller::dispose)
            }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            ServiceSelector(
                                services = state.services,
                                selectedId = serviceId,
                                onSelect = viewModel::selectService,
                            )
                        },
                        actions = {
                            IconButton(onClick = controller::reconnect) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "重新连接终端")
                            }
                        },
                    )
                },
            ) { padding ->
                TerminalWebView(controller, Modifier.fillMaxSize().padding(padding))
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ServiceSelector(
                        services = state.services,
                        selectedId = serviceId,
                        onSelect = viewModel::selectService,
                    )
                },
            )
        },
    ) { padding ->
        EmptyPane(
            title = if (serviceId == null) "选择服务" else "服务未连接",
            icon = Icons.Outlined.Terminal,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@Composable
private fun TerminalWebView(controller: RemoteTerminalController, modifier: Modifier) {
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(Color.rgb(16, 18, 20))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowContentAccess = false
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportZoom(false)
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = request.url.toString() != TERMINAL_ASSET
                    }
                    addJavascriptInterface(controller.bridge, "CodexTerminal")
                    controller.attach(this)
                    loadUrl(TERMINAL_ASSET)
                }
            },
        )
    }
}

private class RemoteTerminalController(
    private val viewModel: MainViewModel,
    private val cwd: String?,
) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var webSocket: WebSocket? = null
    private var reconnectTask: Runnable? = null
    private var generation = 0
    private var cols = 100
    private var rows = 30
    private var pageReady = false
    private var disposed = false
    private var terminalExited = false

    val bridge = Bridge(this)

    fun attach(view: WebView) {
        webView = view
    }

    fun reconnect() {
        main.post {
            if (disposed || !pageReady) return@post
            terminalExited = false
            evaluate("window.remoteTerminal.reset()")
            connect()
        }
    }

    fun dispose() {
        disposed = true
        generation += 1
        reconnectTask?.let(main::removeCallbacks)
        reconnectTask = null
        webSocket?.close(1000, "Terminal view closed")
        webSocket = null
        webView?.let { view ->
            view.removeJavascriptInterface("CodexTerminal")
            view.stopLoading()
            view.destroy()
        }
        webView = null
    }

    private fun ready(newCols: Int, newRows: Int) {
        main.post {
            if (disposed) return@post
            cols = newCols.coerceIn(2, 500)
            rows = newRows.coerceIn(1, 300)
            pageReady = true
            connect()
        }
    }

    private fun input(data: String) {
        if (data.length > MAX_INPUT_CHARS) return
        webSocket?.send(JSONObject().put("type", "input").put("data", data).toString())
    }

    private fun resize(newCols: Int, newRows: Int) {
        cols = newCols.coerceIn(2, 500)
        rows = newRows.coerceIn(1, 300)
        webSocket?.send(
            JSONObject()
                .put("type", "resize")
                .put("cols", cols)
                .put("rows", rows)
                .toString(),
        )
    }

    private fun connect() {
        if (disposed || !pageReady) return
        reconnectTask?.let(main::removeCallbacks)
        reconnectTask = null
        generation += 1
        val activeGeneration = generation
        webSocket?.cancel()
        setStatus("正在连接终端", false)
        webSocket = viewModel.openTerminal(cols, rows, cwd, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                postIfCurrent(activeGeneration) { setStatus("正在等待 Agent", false) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                postIfCurrent(activeGeneration) {
                    when (message.optString("type")) {
                        "ready" -> {
                            setStatus("", false)
                            evaluate("window.remoteTerminal.focus()")
                        }
                        "data" -> write(message.optString("data"))
                        "exit" -> {
                            terminalExited = true
                            setStatus("终端已退出 (${message.optInt("exitCode")})", false)
                        }
                        "error" -> setStatus(message.optString("message", "终端连接失败"), true)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                postIfCurrent(activeGeneration) {
                    if (!terminalExited) scheduleReconnect("终端连接已断开")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                postIfCurrent(activeGeneration) {
                    scheduleReconnect(t.message ?: "终端连接失败")
                }
            }
        })
        if (webSocket == null) setStatus("选择一个已连接的服务", true)
    }

    private fun scheduleReconnect(message: String) {
        if (disposed || terminalExited || reconnectTask != null) return
        setStatus("$message，正在重连", true)
        val task = Runnable {
            reconnectTask = null
            connect()
        }
        reconnectTask = task
        main.postDelayed(task, RECONNECT_DELAY_MS)
    }

    private fun postIfCurrent(expectedGeneration: Int, block: () -> Unit) {
        main.post {
            if (!disposed && generation == expectedGeneration) block()
        }
    }

    private fun write(data: String) {
        evaluate("window.remoteTerminal.write(${JSONObject.quote(data)})")
    }

    private fun setStatus(message: String, error: Boolean) {
        evaluate(
            "window.remoteTerminal.setStatus(${JSONObject.quote(message)}, $error)",
        )
    }

    private fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    class Bridge(private val controller: RemoteTerminalController) {
        @JavascriptInterface
        fun onReady(cols: Int, rows: Int) = controller.ready(cols, rows)

        @JavascriptInterface
        fun onInput(data: String) = controller.input(data)

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) = controller.resize(cols, rows)
    }

    private companion object {
        const val MAX_INPUT_CHARS = 64 * 1024
        const val RECONNECT_DELAY_MS = 2_500L
    }
}

private const val TERMINAL_ASSET = "file:///android_asset/terminal/index.html"
