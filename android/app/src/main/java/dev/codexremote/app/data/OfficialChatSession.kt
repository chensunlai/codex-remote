package dev.codexremote.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.codexremote.app.model.FilePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

internal data class ExtensionSurface(val id: String, val title: String)

/** An opaque public WebView message transport. Closing a renderer never stops the Agent host. */
internal class OfficialChatSession(
    private val context: Context,
    private val api: GatewayApi,
    private val store: OfficialFrontendStore,
    private val serviceId: String,
    private val scope: String,
    private val clientId: String,
    private val workspace: String,
    private val onStatus: (String?) -> Unit,
    private val onOpenFile: (FilePreview) -> Unit,
    private val onPickFiles: suspend (JSONObject) -> JSONArray,
    private val onSaveFile: suspend (JSONObject) -> String?,
    private val onNotification: suspend (String, JSONObject) -> String?,
    private val onExternalUrl: suspend (String) -> Boolean,
    private val onSurfacesChanged: (List<ExtensionSurface>) -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var rendererId = UUID.randomUUID().toString()
    private class Surface(val id: String, var title: String = "Codex") {
        var view: WebView? = null
        var loader: ((String, String, String) -> Unit)? = null
        @Volatile var documentId = ""
        var html = ""
        var pageReady = false
        val queue = mutableListOf<Any>()
    }
    private val surfaces = java.util.concurrent.ConcurrentHashMap<String, Surface>().apply { put("main", Surface("main")) }
    private val surfaceOrder = mutableListOf<String>()
    private var origin = ""
    private var socket: WebSocket? = null
    private var connectionGeneration = 0
    private var sequence = 0L
    private var epoch: String? = null
    private var viewId = ""
    private var connected = false
    private var closed = false
    private var visible = true
    private var reconnect: Runnable? = null
    private var mustRecreatePage = false
    private var recoveryNotice = false
    private var reloadScheduled = false
    private val unconfirmed = mutableMapOf<String, String>()
    private data class PendingMessage(val surfaceId: String, val documentId: String, val payload: JSONArray, val bytes: Int)
    // These messages have never been accepted by WebSocket.send, so sending them once is safe.
    private val pendingMessages = ArrayDeque<PendingMessage>()
    private var pendingBytes = 0
    @Volatile var packageSha256: String? = null
        private set
    val resourceScope: String get() = scope
    private var resourceFailureReported = false
    private val handledRequests = mutableSetOf<String>()
    private val nativeJobs = mutableMapOf<String, Job>()
    private var nativeGeneration = 0L
    private val nativeSurfaces = mutableMapOf<String, String>()
    // Only host-operation responses can be resent. Official page actions are never replayed.
    private val hostResponses = linkedMapOf<String, JSONObject>()
    fun bridge(surfaceId: String) = Bridge(surfaceId)

    fun attach(webView: WebView, loader: (String, String, String) -> Unit) = attachSurface("main", webView, loader)

    fun attachSurface(id: String, webView: WebView, loader: (String, String, String) -> Unit) {
        val surface = surfaces.getOrPut(id) { Surface(id) }
        surface.view = webView
        surface.loader = loader
        if (surface.html.isNotEmpty()) loader(origin, surface.html, surface.documentId)
        if (id == "main") connect()
    }

    fun detachSurface(id: String, webView: WebView) {
        surfaces[id]?.takeIf { it.view === webView }?.let { it.view = null; it.loader = null; it.pageReady = false }
    }

    fun closeSurface(id: String) {
        if (id != "main" && connected) socket?.send(JSONObject().put("type", "close-surface").put("surfaceId", id).toString())
    }

    private fun publishSurfaces() = onSurfacesChanged(surfaceOrder.mapNotNull { id -> surfaces[id]?.let { ExtensionSurface(id, it.title) } })

    private fun removeSurface(id: String) {
        if (id == "main") return
        nativeSurfaces.filterValues { it == id }.keys.toList().forEach { request ->
            nativeJobs.remove(request)?.cancel()
            nativeSurfaces.remove(request)
            handledRequests.remove(request)
            hostResponses.remove(request)
        }
        surfaces.remove(id)
        surfaceOrder.remove(id)
        unconfirmed.entries.removeAll { it.value == id }
        discardPending(id)
        publishSurfaces()
    }

    private fun setHtml(surface: Surface, html: String, force: Boolean = false) {
        if (!force && surface.html == html && surface.documentId.isNotEmpty()) return
        surface.html = html
        surface.documentId = UUID.randomUUID().toString()
        surface.pageReady = false
        surface.queue.clear()
        discardPending(surface.id)
        surface.loader?.invoke(origin, html, surface.documentId)
    }

    private fun reconcileSurfaces(snapshot: JSONObject, force: Boolean) {
        val panels = snapshot.optJSONArray("surfaces")?.objects().orEmpty().filter { it.optString("id") != "main" }
        val ids = panels.map { it.getString("id") }.toSet()
        surfaceOrder.toList().filter { it !in ids }.forEach(::removeSurface)
        panels.forEach { panel ->
            val id = panel.getString("id")
            val surface = surfaces.getOrPut(id) { Surface(id) }
            surface.title = panel.optString("title", "Codex")
            if (id !in surfaceOrder) surfaceOrder.add(id)
            setHtml(surface, panel.optString("html"), force)
        }
        publishSurfaces()
    }

    fun setVisible(value: Boolean) {
        visible = value
        if (connected) socket?.send(JSONObject().put("type", "visibility").put("visible", value).toString())
    }

    fun close() {
        closed = true
        connectionGeneration += 1
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        socket?.close(1000, "Renderer closed")
        socket = null
        surfaces.values.forEach { it.view = null; it.loader = null; it.queue.clear() }
        cancelNativeRequests()
        hostResponses.clear()
        unconfirmed.clear()
        pendingMessages.clear()
        pendingBytes = 0
        jobs.cancel()
    }

    private fun connect() {
        if (closed || socket != null) return
        val attempt = ++connectionGeneration
        onStatus("正在连接官方扩展")
        socket = api.extensionChannel(serviceId, clientId, rendererId, workspace, sequence, epoch,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // OkHttp invokes these callbacks in wire order. Parse large snapshots away from the UI thread.
                    val parsed = runCatching { JSONObject(text) }
                    main.post {
                        if (!closed && attempt == connectionGeneration) {
                            parsed.mapCatching(::receive).onFailure { onStatus("扩展连接错误：${it.message}") }
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(if (code == 1005) 1000 else code, reason)
                    main.post { disconnected(attempt, "$code ${reason.take(160)}") }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { disconnected(attempt, response?.let { "HTTP ${it.code} ${t.message}" } ?: t.message) }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    main.post { disconnected(attempt, "$code ${reason.take(160)}") }
                }
            })
    }

    private fun disconnected(attempt: Int, reason: String?) {
        if (closed || attempt != connectionGeneration) return
        connectionGeneration += 1 // Fence the old socket's later onClosed/onFailure and queued messages.
        if (unconfirmed.isNotEmpty()) invalidateRenderer()
        connected = false
        socket = null
        android.util.Log.i("OfficialCodex", "Extension disconnected: ${reason?.take(180).orEmpty()}")
        onStatus("连接已断开，正在重连。未确认的操作不会自动重发。${reason?.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()}")
        reconnect?.let(main::removeCallbacks)
        reconnect = Runnable { reconnect = null; connect() }.also { main.postDelayed(it, 1500) }
    }

    private fun receive(packet: JSONObject) {
        when (packet.optString("type")) {
            "ready" -> {
                val snapshot = packet.optJSONObject("snapshot") ?: packet
                val nextEpoch = snapshot.getString("epoch")
                val nextView = snapshot.getString("viewId")
                val nextHtml = snapshot.getString("html")
                val nextOrigin = snapshot.getString("origin")
                require(Uri.parse(nextOrigin).let { it.scheme == "https" && it.host?.endsWith(".codex-remote.invalid") == true }) { "无效的扩展页面来源" }
                if (epoch != nextEpoch || viewId != nextView) cancelNativeRequests()
                val changedHost = epoch != nextEpoch || viewId != nextView
                val force = mustRecreatePage || changedHost || snapshot.optBoolean("resyncRequired")
                if (force) sequence = 0
                epoch = nextEpoch
                viewId = nextView
                origin = nextOrigin
                packageSha256 = snapshot.optString("packageSha256").lowercase().takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                connected = true
                mustRecreatePage = false
                setHtml(surfaces.getValue("main"), nextHtml, force)
                reconcileSurfaces(snapshot, force)
                snapshot.optJSONArray("frames")?.objects()?.forEach(::frame)
                snapshot.optJSONArray("pendingHostRequests")?.objects()?.forEach(::hostRequest)
                // A snapshot may omit historical frames that predate the current document.
                sequence = maxOf(sequence, snapshot.optLong("sequence", sequence))
                acknowledge()
                setVisible(visible)
                hostResponses.values.forEach { socket?.send(it.toString()) }
                onStatus(if (recoveryNotice) "连接已恢复，请确认操作结果；未确认的操作未自动重发。" else null)
                recoveryNotice = false
                flushPending()
            }
            "delivery" -> {
                unconfirmed.remove(packet.optString("messageId"))
                if (!packet.optBoolean("accepted", true)) reconnectRenderer()
                else flushPending()
            }
            "frame" -> packet.optJSONObject("frame")?.let(::frame)
            "resync-required" -> reconnectRenderer()
            "error" -> {
                onStatus(packet.optString("message").ifBlank { packet.optJSONObject("error")?.optString("message") ?: "官方扩展连接失败" })
                if (unconfirmed.remove(packet.optString("messageId")) != null) reconnectRenderer()
            }
            else -> if (packet.has("sequence")) frame(packet)
        }
    }

    private fun frame(frame: JSONObject) {
        if (frame.optString("epoch") != epoch || frame.optString("viewId") != viewId) return
        val next = frame.getLong("sequence")
        if (next <= sequence) return
        when (frame.getString("type")) {
            "message" -> {
                val surface = surfaces[frame.optString("surfaceId", "main")]
                if (surface != null) {
                    val payload = frame.get("payload")
                    if (surface.pageReady) deliver(surface, payload) else surface.queue.add(payload)
                }
            }
            "html" -> {
                val surface = surfaces[frame.optString("surfaceId", "main")]
                val payload = frame.opt("payload")
                val html = if (payload is JSONObject) payload.optString("html") else payload as? String
                if (surface != null && html != null) setHtml(surface, html)
            }
            "surface" -> {
                val info = frame.getJSONObject("payload")
                val id = info.getString("id")
                if (info.optString("action") == "close") removeSurface(id)
                else if (id != "main") {
                    val surface = surfaces.getOrPut(id) { Surface(id) }
                    surface.title = info.optString("title", surface.title)
                    if (info.optString("action") == "open") surfaceOrder.remove(id)
                    if (id !in surfaceOrder) surfaceOrder.add(id)
                    if (info.has("html")) setHtml(surface, info.getString("html"))
                    publishSurfaces()
                }
            }
            "host-request" -> hostRequest(frame.getJSONObject("payload").apply { if (!has("surfaceId")) put("surfaceId", frame.optString("surfaceId", "main")) })
            "status" -> frame.optJSONObject("payload")?.let { status ->
                status.optString("message").takeIf(String::isNotBlank)?.let(onStatus)
                val requiresReload = status.optString("state") == "reload" || status.optBoolean("reloadRequired",
                    status.optString("state") == "error" && status.optBoolean("retryable"))
                if (requiresReload && !reloadScheduled) {
                    reloadScheduled = true
                    connected = false
                    main.post { if (!closed && reloadScheduled) reconnectRenderer() }
                }
            }
        }
        sequence = next
        acknowledge()
    }

    private fun acknowledge() {
        if (connected) socket?.send(JSONObject().put("type", "ack").put("sequence", sequence).toString())
    }

    private fun invalidateRenderer() {
        cancelNativeRequests()
        mustRecreatePage = true
        recoveryNotice = true
        rendererId = UUID.randomUUID().toString()
        epoch = null
        sequence = 0
        surfaces.values.forEach { it.documentId = ""; it.pageReady = false; it.queue.clear() }
        unconfirmed.clear()
        pendingMessages.clear()
        pendingBytes = 0
    }

    private fun cancelNativeRequests() {
        nativeGeneration += 1
        nativeJobs.values.toList().forEach { it.cancel() }
        nativeJobs.clear()
        nativeSurfaces.clear()
        handledRequests.clear()
        hostResponses.clear()
    }

    private fun reconnectRenderer() {
        if (closed) return
        reloadScheduled = false
        connectionGeneration += 1
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        socket?.close(1000, "Renderer requires resynchronization")
        socket = null
        connected = false
        invalidateRenderer()
        connect()
    }

    private fun discardPending(surfaceId: String) {
        val removed = pendingMessages.filter { it.surfaceId == surfaceId }
        pendingMessages.removeAll(removed.toSet())
        pendingBytes -= removed.sumOf { it.bytes }
    }

    private fun flushPending() {
        while (connected && unconfirmed.size < 256 && pendingMessages.isNotEmpty()) {
            val message = pendingMessages.first()
            if (surfaces[message.surfaceId]?.documentId != message.documentId) {
                pendingMessages.removeFirst()
                pendingBytes -= message.bytes
                continue
            }
            val messageId = UUID.randomUUID().toString()
            val packet = JSONObject().put("type", "message").put("messageId", messageId)
                .put("epoch", epoch).put("surfaceId", message.surfaceId).put("payload", message.payload)
            val active = socket
            if (active?.send(packet.toString()) != true) {
                disconnected(connectionGeneration, "发送连接不可用")
                active?.cancel()
                return
            }
            pendingMessages.removeFirst()
            pendingBytes -= message.bytes
            unconfirmed[messageId] = message.surfaceId
        }
    }

    fun resourceFailed(failure: Throwable) {
        main.post {
            if (!closed && !resourceFailureReported) {
                resourceFailureReported = true
                onStatus("官方前端资源加载失败：${failure.message?.take(180).orEmpty()}。请在右上角菜单重新加载前端。")
            }
        }
    }

    private fun deliver(surface: Surface, payload: Any) {
        val encoded = JSONObject.quote(payload.toString())
        surface.view?.evaluateJavascript("window.__codexExtensionHost?.receive(JSON.parse($encoded))", null)
    }

    private fun hostRequest(request: JSONObject) {
        val id = request.getString("id")
        hostResponses[id]?.let { socket?.send(it.toString()); return }
        if (!handledRequests.add(id)) return
        val requestEpoch = epoch
        val requestGeneration = nativeGeneration
        val surfaceId = request.optString("surfaceId", "main")
        nativeSurfaces[id] = surfaceId
        val requestJob = jobs.launch(start = CoroutineStart.LAZY) {
            try {
                val response = JSONObject().put("type", "host-response").put("id", id)
                try {
                    val result = handleHostRequest(request.getString("method"), request.optJSONObject("params") ?: JSONObject())
                    response.put("result", result ?: JSONObject.NULL)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { response.put("error", JSONObject().put("message", failure.message ?: "宿主操作失败")) }
                if (!closed && requestEpoch == epoch && requestGeneration == nativeGeneration && nativeSurfaces[id] == surfaceId) {
                    hostResponses[id] = response
                    while (hostResponses.size > 128) hostResponses.remove(hostResponses.keys.first())
                    if (connected) socket?.send(response.toString())
                }
            } finally {
                if (requestGeneration == nativeGeneration) nativeJobs.remove(id)
            }
        }
        nativeJobs[id] = requestJob
        requestJob.start()
    }

    private suspend fun handleHostRequest(method: String, params: JSONObject): Any? = when (method) {
        "showOpenDialog" -> {
            val filters = params.optJSONObject("filters")
            val extensions = JSONArray()
            filters?.keys()?.forEach { key -> filters.optJSONArray(key)?.let { values ->
                for (i in 0 until values.length()) if (values.optString(i) != "*") extensions.put(values.getString(i))
            } }
            val files = params.optBoolean("canSelectFiles", true)
            val folders = params.optBoolean("canSelectFolders")
            val selection = onPickFiles(JSONObject().put("kind", if (folders && files) "mixed" else if (folders) "directory" else "file")
                .put("allowMultiple", params.optBoolean("canSelectMany"))
                .put("acceptedFileExtensions", extensions))
            JSONArray().apply { selection.objects().forEach { file -> put(Uri.fromFile(java.io.File(file.getString("path"))).toString()) } }
        }
        "showSaveDialog" -> onSaveFile(params)
        "showInformationMessage", "showWarningMessage", "showErrorMessage" -> onNotification(method, params)
        "clipboard.read" -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        "clipboard.write" -> {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Codex", params.optString("text")))
            null
        }
        "openExternal" -> onExternalUrl(params.getString("url"))
        "openFile" -> {
            val path = params.getString("path")
            onOpenFile(FilePreview(path.substringAfterLast('/'), path, api.preview(serviceId, path)))
            true
        }
        else -> error("当前移动端宿主不支持：$method")
    }

    fun requestExternal(url: String) {
        jobs.launch { onExternalUrl(url) }
    }

    fun openExternal(url: String): Boolean {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" || uri.scheme == "http") { "不支持的链接类型" }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    inner class Bridge(private val surfaceId: String) {
        private fun surface(page: String) = surfaces[surfaceId]?.takeIf { it.documentId == page }
        @JavascriptInterface fun getState(page: String): String = if (surface(page) != null) store.state(scope, "$viewId:$surfaceId") else "null"
        @JavascriptInterface fun setState(page: String, json: String) {
            if (surface(page) == null) return
            runCatching { JSONTokener(json).nextValue() }.onSuccess { store.setState(scope, "$viewId:$surfaceId", json) }
        }
        @JavascriptInterface fun postMessage(page: String, encoded: String) {
            val payload = runCatching { JSONArray(encoded) }.getOrNull() ?: return
            val bytes = encoded.toByteArray(Charsets.UTF_8).size
            main.post {
                if (closed || surface(page) == null) return@post
                if (pendingMessages.size >= 256 || bytes > 16 * 1024 * 1024 - pendingBytes) {
                    onStatus("待发送操作过多，正在恢复页面；未确认的操作不会自动重发。")
                    reconnectRenderer()
                    return@post
                }
                pendingMessages.addLast(PendingMessage(surfaceId, page, payload, bytes))
                pendingBytes += bytes
                flushPending()
            }
        }
        @JavascriptInterface fun ready(page: String) {
            main.post {
                if (closed) return@post
                val surface = surface(page) ?: return@post
                surface.pageReady = true
                surface.queue.toList().forEach { deliver(surface, it) }
                surface.queue.clear()
            }
        }
    }
}
