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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.TerminalSession
import dev.codexremote.app.data.TerminalSessionObserver
import dev.codexremote.app.data.TerminalSnapshot
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.RuntimeState
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(state: AppState, viewModel: MainViewModel) {
    val serviceId = state.selectedServiceId
    val service = state.services.firstOrNull { it.id == serviceId }

    if (serviceId != null && service?.runtimeState == RuntimeState.CONNECTED) {
        key(serviceId) {
            val session = remember(serviceId) {
                viewModel.terminalSession(serviceId, service.home)
            }
            val controller = remember(session) {
                RemoteTerminalController(session)
            }
            DisposableEffect(controller) {
                onDispose(controller::detach)
            }
            LaunchedEffect(controller, state.fontScale) {
                controller.setFontScale(state.fontScale)
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
                                Icon(CodexIcons.Refresh, contentDescription = "重新连接终端")
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
            icon = CodexIcons.Terminal,
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
    private val session: TerminalSession,
) : TerminalSessionObserver {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var pageReady = false
    private var fontScale = 1f

    val bridge = Bridge(this)

    fun attach(view: WebView) {
        webView = view
    }

    fun reconnect() {
        session.reconnect()
    }

    fun setFontScale(value: Float) {
        main.post {
            fontScale = value.coerceIn(UiPreferences.MIN_FONT_SCALE, UiPreferences.MAX_FONT_SCALE)
            if (pageReady) evaluate("window.remoteTerminal.setFontScale($fontScale)")
        }
    }

    fun detach() {
        main.post {
            session.detach(this)
            pageReady = false
            webView?.let { view ->
                view.removeJavascriptInterface("CodexTerminal")
                view.stopLoading()
                view.destroy()
            }
            webView = null
        }
    }

    private fun ready(newCols: Int, newRows: Int) {
        main.post {
            if (webView == null) return@post
            pageReady = true
            evaluate("window.remoteTerminal.setFontScale($fontScale)")
            session.attach(this)
            session.ready(newCols, newRows)
        }
    }

    private fun input(data: String) {
        session.input(data)
    }

    private fun resize(newCols: Int, newRows: Int) {
        session.resize(newCols, newRows)
    }

    override fun onTerminalSnapshot(snapshot: TerminalSnapshot) {
        if (!pageReady) return
        evaluate("window.remoteTerminal.reset()")
        writeChunked(snapshot.output)
        setStatus(snapshot.status, snapshot.statusIsError)
        if (snapshot.status.isEmpty() && !snapshot.exited) focus()
    }

    override fun onTerminalData(data: String) {
        if (pageReady) write(data)
    }

    override fun onTerminalStatus(message: String, isError: Boolean) {
        if (!pageReady) return
        setStatus(message, isError)
        if (message.isEmpty()) focus()
    }

    private fun write(data: String) {
        evaluate("window.remoteTerminal.write(${JSONObject.quote(data)})")
    }

    private fun writeChunked(data: String) {
        var start = 0
        while (start < data.length) {
            var end = minOf(data.length, start + REPLAY_CHUNK_CHARS)
            if (
                end < data.length &&
                data[end - 1].isHighSurrogate() &&
                data[end].isLowSurrogate()
            ) {
                end -= 1
            }
            write(data.substring(start, end))
            start = end
        }
    }

    private fun setStatus(message: String, error: Boolean) {
        evaluate(
            "window.remoteTerminal.setStatus(${JSONObject.quote(message)}, $error)",
        )
    }

    private fun evaluate(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    private fun focus() {
        evaluate("window.remoteTerminal.focus()")
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
        const val REPLAY_CHUNK_CHARS = 16 * 1024
    }
}

private const val TERMINAL_ASSET = "file:///android_asset/terminal/index.html"
