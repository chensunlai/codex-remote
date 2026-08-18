package dev.codexremote.app.data

import android.os.Handler
import android.os.Looper
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

internal data class TerminalSnapshot(
    val output: String,
    val status: String,
    val statusIsError: Boolean,
    val exited: Boolean,
)

internal interface TerminalSessionObserver {
    fun onTerminalSnapshot(snapshot: TerminalSnapshot)
    fun onTerminalData(data: String)
    fun onTerminalStatus(message: String, isError: Boolean)
}

internal class TerminalSessionManager(private val api: GatewayApi) {
    private val sessions = mutableMapOf<String, TerminalSession>()

    fun session(serviceId: String, cwd: String?): TerminalSession =
        sessions.getOrPut(serviceId) { TerminalSession(api, serviceId, cwd) }
            .also { it.updateInitialCwd(cwd) }

    fun close(serviceId: String) {
        sessions.remove(serviceId)?.close()
    }

    fun closeAll() {
        sessions.values.forEach(TerminalSession::close)
        sessions.clear()
    }
}

internal class TerminalSession(
    private val api: GatewayApi,
    private val serviceId: String,
    initialCwd: String?,
) {
    private val main = Handler(Looper.getMainLooper())
    private val output = TerminalOutputBuffer(MAX_BUFFER_CHARS)
    private var cwd = initialCwd
    private var observer: TerminalSessionObserver? = null
    private var webSocket: WebSocket? = null
    private var reconnectTask: Runnable? = null
    private var generation = 0
    private var cols = 100
    private var rows = 30
    private var started = false
    private var closed = false
    private var exited = false
    private var status = "正在准备终端"
    private var statusIsError = false

    fun attach(value: TerminalSessionObserver) = onMain {
        observer = value
        value.onTerminalSnapshot(snapshot())
    }

    fun detach(value: TerminalSessionObserver) = onMain {
        if (observer === value) observer = null
    }

    fun ready(newCols: Int, newRows: Int) = onMain {
        if (closed) return@onMain
        cols = newCols.coerceIn(20, 500)
        rows = newRows.coerceIn(5, 200)
        started = true
        if (webSocket == null && reconnectTask == null && !exited) {
            connect()
        } else {
            sendResize()
        }
    }

    fun input(data: String) = onMain {
        if (data.length > MAX_INPUT_CHARS || closed) return@onMain
        webSocket?.send(JSONObject().put("type", "input").put("data", data).toString())
    }

    fun resize(newCols: Int, newRows: Int) = onMain {
        cols = newCols.coerceIn(20, 500)
        rows = newRows.coerceIn(5, 200)
        sendResize()
    }

    fun reconnect() = onMain {
        if (closed || !started) return@onMain
        generation += 1
        reconnectTask?.let(main::removeCallbacks)
        reconnectTask = null
        webSocket?.close(1000, "Terminal reconnect requested")
        webSocket = null
        exited = false
        output.clear()
        observer?.onTerminalSnapshot(snapshot())
        connect()
    }

    fun updateInitialCwd(value: String?) = onMain {
        if (!started && !value.isNullOrBlank()) cwd = value
    }

    fun close() = onMain {
        if (closed) return@onMain
        closed = true
        generation += 1
        reconnectTask?.let(main::removeCallbacks)
        reconnectTask = null
        webSocket?.close(1000, "Android client closed")
        webSocket = null
        observer = null
    }

    private fun connect() {
        if (closed || !started || exited) return
        reconnectTask?.let(main::removeCallbacks)
        reconnectTask = null
        generation += 1
        val activeGeneration = generation
        updateStatus("正在连接终端", false)
        webSocket = runCatching {
            api.terminal(serviceId, cols, rows, cwd, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    postIfCurrent(activeGeneration) {
                        updateStatus("正在等待 Agent", false)
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    postIfCurrent(activeGeneration) {
                        when (message.optString("type")) {
                            "ready" -> updateStatus("", false)
                            "data" -> appendOutput(message.optString("data"))
                            "exit" -> {
                                exited = true
                                updateStatus("终端已退出 (${message.optInt("exitCode")})", false)
                            }
                            "error" -> updateStatus(
                                message.optString("message", "终端连接失败"),
                                true,
                            )
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    postIfCurrent(activeGeneration) {
                        this@TerminalSession.webSocket = null
                        if (!exited) scheduleReconnect("终端连接已断开")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    postIfCurrent(activeGeneration) {
                        this@TerminalSession.webSocket = null
                        scheduleReconnect(t.message ?: "终端连接失败")
                    }
                }
            })
        }.getOrElse { error ->
            scheduleReconnect(error.message ?: "终端连接失败")
            null
        }
    }

    private fun appendOutput(data: String) {
        output.append(data)
        observer?.onTerminalData(data)
    }

    private fun sendResize() {
        webSocket?.send(
            JSONObject()
                .put("type", "resize")
                .put("cols", cols)
                .put("rows", rows)
                .toString(),
        )
    }

    private fun scheduleReconnect(message: String) {
        if (closed || exited || !started || reconnectTask != null) return
        updateStatus("$message，正在重连", true)
        val task = Runnable {
            reconnectTask = null
            connect()
        }
        reconnectTask = task
        main.postDelayed(task, RECONNECT_DELAY_MS)
    }

    private fun updateStatus(message: String, isError: Boolean) {
        status = message
        statusIsError = isError
        observer?.onTerminalStatus(message, isError)
    }

    private fun snapshot() = TerminalSnapshot(
        output = output.value,
        status = status,
        statusIsError = statusIsError,
        exited = exited,
    )

    private fun postIfCurrent(expectedGeneration: Int, block: () -> Unit) {
        main.post {
            if (!closed && generation == expectedGeneration) block()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    private companion object {
        const val MAX_BUFFER_CHARS = 1_000_000
        const val MAX_INPUT_CHARS = 64 * 1024
        const val RECONNECT_DELAY_MS = 2_500L
    }
}
