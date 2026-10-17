package dev.codexremote.app.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import dev.codexremote.app.model.FilePreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Owns only the renderer connection. Disposing it never sends turn/interrupt. */
internal class OfficialChatSession(
    private val context: Context,
    private val api: GatewayApi,
    private val serviceId: String,
    private val clientId: String,
    private val home: String,
    private val workspace: String,
    private val serviceName: String,
    private val onStatus: (String?) -> Unit,
    private val onOpenFile: (FilePreview) -> Unit,
    private val onPickFiles: suspend (JSONObject) -> JSONArray,
    private val onThreadConflict: (String?) -> Unit,
    private val onSettingsChanged: (Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sessionId = UUID.randomUUID().toString()
    private val outbox = linkedMapOf<String, JSONObject>()
    private val pageQueue = mutableListOf<JSONObject>()
    private val deliveredRequests = mutableSetOf<String>()
    private var socket: WebSocket? = null
    private var view: WebView? = null
    private var generation = 0
    private var sequence = 0L
    private var epoch: String? = null
    private var ready = false
    private var connected = false
    private var attachedBefore = false
    private var closed = false
    private var reconnect: Runnable? = null
    private var theme = "dark"
    private var fontScale = 1f
    val bridge = Bridge()

    fun attach(webView: WebView) { view = webView }

    fun exitSettings() {
        if (ready && !closed) deliver(JSONObject().put("type", "exit-settings"))
    }

    fun setTheme(value: String) {
        if (theme == value) return
        theme = value
        if (ready) deliver(JSONObject().put("type", "theme").put("theme", theme))
    }

    fun setFontScale(value: Float) {
        if (!value.isFinite() || value <= 0f || fontScale == value) return
        fontScale = value
        if (ready) deliverFontScale()
    }

    private fun deliverFontScale() {
        deliver(JSONObject().put("type", "font-scale").put("scale", fontScale.toDouble()))
    }

    fun pageStarted() {
        onSettingsChanged(false)
        generation += 1
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        socket?.close(1000, "Renderer reloaded")
        socket = null
        sessionId = UUID.randomUUID().toString()
        sequence = 0
        epoch = null
        ready = false
        connected = false
        attachedBefore = false
        outbox.clear()
        pageQueue.clear()
        deliveredRequests.clear()
    }

    fun close() {
        onSettingsChanged(false)
        closed = true
        generation += 1
        reconnect?.let(main::removeCallbacks)
        socket?.close(1000, "Renderer detached")
        socket = null
        view = null
        jobs.cancel()
    }

    inner class Bridge {
        @JavascriptInterface
        fun postMessage(text: String) {
            if (text.length > 16 * 1024 * 1024) return
            main.post {
                if (!closed) runCatching { handlePage(JSONObject(text)) }
                    .onFailure { onStatus(it.message ?: "前端通信失败") }
            }
        }
    }

    private fun handlePage(message: JSONObject) {
        when (message.optString("type")) {
            "ready" -> {
                ready = true
                deliver(JSONObject().put("type", "theme").put("theme", theme))
                deliverFontScale()
                pageQueue.toList().also { pageQueue.clear() }.forEach(::deliver)
                if (socket == null) connect()
            }
            "rpc", "notification" -> sendRpc(message.getJSONObject("request"))
            "response" -> sendRpc(message.getJSONObject("response"))
            "coordination" -> {
                // Owner/follower commands are live-only; never put them in the RPC outbox.
                val frame = JSONObject().put("type", "coordination").put("epoch", epoch)
                    .put("message", message.getJSONObject("message"))
                if (!connected || socket?.send(frame.toString()) != true) {
                    deliver(JSONObject().put("type", "coordination").put("message", JSONObject().put("type", "disconnected")))
                }
            }
            "host-request" -> handleHostRequest(message)
            "settings-navigation" -> onSettingsChanged(message.optBoolean("inSettings"))
            "thread-conflict" -> {
                onStatus(null)
                onThreadConflict(message.optString("threadId").takeIf { it.isNotBlank() && it != "null" })
            }
            "log" -> if (message.optString("level") == "error") {
                val detail = message.optString("message")
                if (!detail.contains("active writer", ignoreCase = true) && !detail.contains("live local writer", ignoreCase = true)) onStatus(detail)
            }
        }
    }

    private fun connect() {
        if (closed) return
        reconnect?.let(main::removeCallbacks)
        reconnect = null
        val current = ++generation
        connected = false
        onStatus("正在连接 ${serviceName}")
        socket = api.codexChannel(serviceId, clientId, sessionId, sequence, epoch, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (current == generation && !closed) {
                        runCatching { handleServer(JSONObject(text)) }.onFailure { onStatus(it.message) }
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(current, reason, code != 4001 && code != 1008)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(current, t.message ?: "连接断开", response?.code != 401 && response?.code != 403)
        })
    }

    private fun lost(current: Int, message: String, retry: Boolean = true) {
        main.post {
            if (closed || generation != current) return@post
            connected = false
            socket = null
            onStatus(if (retry) "$message；正在重连" else message)
            deliver(JSONObject().put("type", "connection").put("state", if (retry) "disconnected" else "error").put("message", message))
            reconnect?.let(main::removeCallbacks)
            if (retry) reconnect = Runnable { connect() }.also { main.postDelayed(it, 2500) }
        }
    }

    private fun handleServer(frame: JSONObject) {
        when (frame.optString("type")) {
            "ready" -> {
                val resync = attachedBefore && frame.optBoolean("resyncRequired")
                val nextEpoch = frame.getString("epoch")
                if (epoch != null && epoch != nextEpoch) {
                    outbox.values.forEach { pending ->
                        val rpc = pending.getJSONObject("message")
                        if (rpc.has("id") && rpc.has("method")) deliver(JSONObject().put("type", "rpc-message").put("message",
                            JSONObject().put("id", rpc.get("id")).put("error", JSONObject().put("code", -32000)
                                .put("message", "Codex 已重启，先前提交的结果未知；请检查会话历史。"))))
                    }
                    outbox.clear()
                    sequence = 0
                }
                epoch = nextEpoch
                connected = true
                attachedBefore = true
                deliver(JSONObject().put("type", "initialized").put("result", frame.optJSONObject("initializeResult") ?: JSONObject())
                    .put("clientCoordination", frame.optBoolean("clientCoordination"))
                    .put("runningVersion", frame.opt("runningVersion") ?: JSONObject.NULL).put("home", home).put("workspace", workspace).put("serviceName", serviceName))
                frame.optJSONArray("frames")?.objects()?.forEach(::handleServer)
                frame.optJSONArray("pendingRequests")?.objects()?.forEach { request ->
                    val rpc = request.optJSONObject("message") ?: request
                    forwardRpc(rpc)
                }
                sequence = maxOf(sequence, frame.optLong("sequence"))
                socket?.send(JSONObject().put("type", "ack").put("sequence", sequence).toString())
                if (resync) {
                    connected = false
                    deliver(JSONObject().put("type", "connection").put("state", "resync-required"))
                    return
                }
                deliver(JSONObject().put("type", "connection").put("state", "connected"))
                onStatus(null)
                outbox.values.forEach { it.put("epoch", epoch); socket?.send(it.toString()) }
            }
            "rpc", "coordination" -> {
                if (frame.optString("epoch") != epoch) return
                val next = frame.optLong("sequence")
                if (next <= sequence) return
                frame.optJSONObject("message")?.let { message ->
                    if (frame.optString("type") == "rpc") forwardRpc(message)
                    else deliver(JSONObject().put("type", "coordination").put("message", message))
                }
                sequence = next
                socket?.send(JSONObject().put("type", "ack").put("sequence", sequence).toString())
            }
            "delivery" -> {
                val pending = outbox.remove(frame.optString("messageId"))
                if (frame.optString("status") != "accepted" && pending != null) {
                    failRpc(pending.getJSONObject("message"), "提交结果未知，请检查会话历史，避免重复发送。")
                    onStatus("提交结果未知，请检查会话历史。")
                } else if (pending != null) {
                    val message = pending.getJSONObject("message")
                    if (!message.has("method") && message.has("id")) deliveredRequests.remove(JSONArray().put(message.get("id")).toString())
                }
            }
            "closed", "resync-required" -> {
                onStatus(frame.optString("message", "Codex 连接已重置，请重新打开会话"))
                if (frame.optString("type") == "closed") {
                    generation += 1
                    socket?.close(1000, "Codex runtime reset")
                    socket = null
                    connected = false
                    reconnect = Runnable { connect() }.also { main.postDelayed(it, 500) }
                } else deliver(JSONObject().put("type", "connection").put("state", "resync-required"))
            }
            "error" -> {
                val message = frame.optString("message", frame.optJSONObject("error")?.optString("message") ?: "通道错误")
                outbox.remove(frame.optString("messageId"))?.optJSONObject("message")?.let { failRpc(it, message) }
                onStatus(message)
            }
        }
    }

    private fun sendRpc(message: JSONObject) {
        val id = UUID.randomUUID().toString()
        val frame = JSONObject().put("type", "rpc").put("messageId", id).put("epoch", epoch ?: JSONObject.NULL).put("message", message)
        val bytes = frame.toString().toByteArray().size
        val queuedBytes = outbox.values.sumOf { it.toString().toByteArray().size.toLong() }
        if (bytes > 16 * 1024 * 1024 - 512 || outbox.size >= 128 || queuedBytes + bytes > 32L * 1024 * 1024) {
            failRpc(message, "消息或待发送队列超过传输上限，请缩小附件或恢复连接后重试。")
            return
        }
        outbox[id] = frame
        if (connected) socket?.send(frame.toString())
    }

    private fun forwardRpc(message: JSONObject) {
        if (message.has("method") && message.has("id")) {
            val key = JSONArray().put(message.get("id")).toString()
            if (!deliveredRequests.add(key)) return
        } else if (message.optString("method") == "serverRequest/resolved") {
            message.optJSONObject("params")?.opt("requestId")?.let {
                deliveredRequests.remove(JSONArray().put(it).toString())
            }
        }
        deliver(JSONObject().put("type", "rpc-message").put("message", message))
    }

    private fun failRpc(message: JSONObject, error: String) {
        if (message.has("id") && message.has("method")) {
            deliver(JSONObject().put("type", "rpc-message").put("message", JSONObject()
                .put("id", message.get("id")).put("error", JSONObject().put("code", -32000).put("message", error))))
        }
    }

    private fun deliver(message: JSONObject) {
        if (closed) return
        if (!ready) { pageQueue.add(message); return }
        view?.evaluateJavascript("window.codexRemoteReceive && window.codexRemoteReceive(${message});", null)
    }

    private fun handleHostRequest(message: JSONObject) {
        val id = message.get("id")
        val method = message.getString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val pageSession = sessionId
        jobs.launch {
            val response = JSONObject().put("type", "host-response").put("id", id)
            runCatching { hostRequest(method, params) }.onSuccess { response.put("result", it) }
                .onFailure {
                    if (it is CancellationException) throw it
                    response.put("error", JSONObject().put("message", it.message ?: "宿主操作失败"))
                }
            if (pageSession == sessionId) deliver(response)
        }
    }

    private suspend fun hostRequest(method: String, params: JSONObject): Any {
        return when (method) {
            "files/pick" -> JSONObject().put("files", onPickFiles(params))
            "fs/read" -> {
                val output = ByteArrayOutputStream()
                api.download(serviceId, params.getString("path"), output)
                val content = if (params.optString("encoding") == "base64") Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                    else output.toString("UTF-8")
                JSONObject().put("content", content)
            }
            "fs/write" -> {
                val path = params.getString("path")
                val content = params.getString("content")
                val bytes = if (params.optString("encoding") == "base64") Base64.decode(content, Base64.DEFAULT) else content.toByteArray()
                api.upload(serviceId, path, path.substringAfterLast('/'), "application/octet-stream", bytes.size.toLong()) { bytes.inputStream() }
                JSONObject()
            }
            "fs/list" -> JSONObject().put("entries", JSONArray().also { entries ->
                api.files(serviceId, params.getString("path")).objects().forEach { file ->
                    entries.put(JSONObject().put("name", file.optString("name")).put("path", file.optString("path"))
                        .put("isDirectory", file.optString("type") == "directory").put("size", file.optLong("size"))
                        .put("mtimeMs", file.optLong("modifiedAt") * 1000))
                }
            })
            "fs/stat" -> {
                val path = params.getString("path").trimEnd('/').ifBlank { "/" }
                if (path == "/") JSONObject().put("size", 0).put("isDirectory", true)
                else {
                    val parent = path.substringBeforeLast('/').ifBlank { "/" }
                    val file = api.files(serviceId, parent).objects().firstOrNull { it.optString("name") == path.substringAfterLast('/') }
                        ?: error("文件不存在：$path")
                    JSONObject().put("size", file.optLong("size")).put("isDirectory", file.optString("type") == "directory")
                        .put("mtimeMs", file.optLong("modifiedAt") * 1000)
                }
            }
            "fs/mkdir" -> { api.mkdir(serviceId, params.getString("path")); JSONObject() }
            "clipboard/read" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                JSONObject().put("text", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty())
            }
            "clipboard/write" -> {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Codex", params.getString("text")))
                JSONObject()
            }
            "open-url" -> {
                val uri = Uri.parse(params.getString("url"))
                require(uri.scheme == "https" || uri.scheme == "http") { "不支持的链接类型" }
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject()
            }
            "file/open" -> {
                val path = params.getString("path")
                val preview = api.preview(serviceId, path)
                onOpenFile(FilePreview(path.substringAfterLast('/'), path, preview))
                JSONObject()
            }
            else -> error("当前移动端宿主不支持：$method")
        }
    }
}
