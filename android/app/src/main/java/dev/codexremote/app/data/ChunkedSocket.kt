package dev.codexremote.app.data

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal const val TRANSPORT_WIRE_BYTES = 32 * 1024 * 1024
internal const val TRANSPORT_CHUNK_BYTES = TRANSPORT_WIRE_BYTES - 16
// OkHttp has a fixed 16 MiB send queue. Keep each upload below that limit.
internal const val TRANSPORT_UPLOAD_BYTES = 8 * 1024 * 1024
private const val UPLOAD_CHUNK_BYTES = TRANSPORT_UPLOAD_BYTES - 16
private const val HIGH_WATER_BYTES = 3 * 1024 * 1024
internal const val TRANSPORT_MAX_BYTES = 512 * 1024 * 1024

/** Pure byte reassembly, shared wire format with transport/websocket.cjs. */
internal class FragmentDecoder(var limit: Int = TRANSPORT_MAX_BYTES) {
    private var bytes: ByteArray? = null
    private var transferId = 0
    private var offset = 0
    val active: Boolean get() = bytes != null

    fun text(value: String): String {
        require(!active) { "Message interrupted an unfinished transfer" }
        val size = value.toByteArray(Charsets.UTF_8).size
        if (size > limit) throw MessageLimitException()
        require(size <= TRANSPORT_WIRE_BYTES) { "Large messages must be fragmented" }
        return value
    }

    fun binary(part: ByteArray): String? {
        require(part.size in 17..(TRANSPORT_CHUNK_BYTES + 16)) { "Invalid fragment size" }
        val header = ByteBuffer.wrap(part).order(ByteOrder.BIG_ENDIAN)
        require(header.int == 0x43525731) { "Invalid fragment marker" }
        val id = header.int
        val position = header.int.toLong() and 0xffffffffL
        val total = header.int.toLong() and 0xffffffffL
        if (total > limit) throw MessageLimitException()
        require(total > 0 && position + part.size - 16 <= total) { "Invalid fragment length" }
        if (!active) {
            require(position == 0L) { "Fragment starts at a nonzero offset" }
            bytes = ByteArray(total.toInt())
            transferId = id
            offset = 0
        }
        val target = requireNotNull(bytes)
        require(id == transferId && total == target.size.toLong() && position == offset.toLong()) { "Out-of-order fragment" }
        part.copyInto(target, offset, 16)
        offset += part.size - 16
        if (offset != target.size) return null
        clear()
        // CharsetDecoder.decode(buffer) allocates a UTF-16 buffer proportional to
        // the entire message. Validate with fixed scratch space, then use ART's
        // compact String decoder for the final complete message.
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(target)
        val scratch = java.nio.CharBuffer.allocate(8192)
        while (true) {
            val result = decoder.decode(input, scratch, true)
            if (result.isError) result.throwException()
            if (result.isUnderflow) break
            scratch.clear()
        }
        return String(target, Charsets.UTF_8)
    }

    fun clear() { bytes = null; offset = 0 }
}
internal class MessageLimitException : IllegalArgumentException("完整消息超过协商上限，请调整 Gateway 会话同步消息上限后重连")

/** Native-only transport; the official WebView receives complete original messages. */
internal class ChunkedSocket(private val downstream: WebSocketListener) : WebSocketListener() {
    private val decoder = FragmentDecoder()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "Codex transport writer").apply { isDaemon = true } }
    private var timer: ScheduledFuture<*>? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var disposed = false
    @Volatile private var negotiated = false
    @Volatile var maxMessageBytes: Int = TRANSPORT_MAX_BYTES
        private set
    @Volatile var failureCode: Int? = null
        private set
    @Volatile var failureMessage: String? = null
        private set
    private var queuedBytes = 0L
    private var transferId = 0

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        armTimeout()
    }

    override fun onMessage(webSocket: WebSocket, text: String) = receive(webSocket) {
        if (!negotiated) {
            val hello = JSONObject(text)
            if (hello.optString("type") == "error") error(hello.optString("message", "请升级 Agent 或 Gateway"))
            require(hello.optString("type") == "transport-config" && hello.optString("protocol") == "crw1" &&
                hello.optInt("chunkBytes") == TRANSPORT_CHUNK_BYTES) { "Gateway 不支持当前分块协议，请升级 Gateway" }
            val limit = hello.getLong("maxMessageBytes")
            require(limit in 1..TRANSPORT_MAX_BYTES.toLong()) { "无效的传输上限" }
            maxMessageBytes = limit.toInt()
            decoder.limit = maxMessageBytes
            check(webSocket.send(JSONObject().put("type", "transport-ready").put("protocol", "crw1")
                .put("chunkBytes", TRANSPORT_CHUNK_BYTES).put("maxMessageBytes", maxMessageBytes).toString()))
            negotiated = true
            timer?.cancel(false)
        } else downstream.onMessage(webSocket, decoder.text(text))
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(webSocket) {
        require(negotiated) { "Expected transport handshake" }
        val text = decoder.binary(bytes.toByteArray())
        if (text == null) armTimeout()
        else { timer?.cancel(false); downstream.onMessage(webSocket, text) }
    }

    private fun receive(webSocket: WebSocket, action: () -> Unit) {
        if (disposed) return
        try { action() }
        catch (_: OutOfMemoryError) {
            decoder.clear()
            fail(webSocket, 4409, "手机内存不足以处理完整消息，请降低 Gateway 会话同步上限")
        }
        catch (error: Exception) { fail(webSocket, if (error is MessageLimitException) 4409 else 4400, error.message ?: "Invalid transport message") }
    }

    @Synchronized fun send(text: String): Boolean {
        if (disposed || !negotiated) return false
        val bytes = try { text.toByteArray(Charsets.UTF_8) }
        catch (_: OutOfMemoryError) {
            socket?.let { fail(it, 4409, "手机内存不足以发送完整消息，请降低 Gateway 会话同步上限") }
            return false
        }
        if (bytes.size > maxMessageBytes) { socket?.let { fail(it, 4409, MessageLimitException().message!!) }; return false }
        if (queuedBytes + bytes.size > maxMessageBytes.toLong() + 8 * 1024 * 1024) {
            socket?.let { fail(it, 1013, "Transport send queue full; reconnect to resume") }; return false
        }
        queuedBytes += bytes.size
        writer.execute {
            try {
                val active = socket ?: error("Transport disconnected")
                if (bytes.size <= TRANSPORT_UPLOAD_BYTES) write(active) { active.send(text) }
                else {
                    val id = ++transferId
                    var offset = 0
                    while (offset < bytes.size) {
                        val length = minOf(UPLOAD_CHUNK_BYTES, bytes.size - offset)
                        val part = ByteBuffer.allocate(16 + length).order(ByteOrder.BIG_ENDIAN)
                            .putInt(0x43525731).putInt(id).putInt(offset).putInt(bytes.size).put(bytes, offset, length).array()
                        write(active) { active.send(part.toByteString()) }
                        offset += length
                    }
                }
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (failure: Exception) { socket?.let { fail(it, 1012, failure.message ?: "Transport send failed") } }
            finally { synchronized(this) { queuedBytes -= bytes.size } }
        }
        return true
    }

    private fun write(active: WebSocket, send: () -> Boolean) {
        var progress = System.nanoTime()
        var previous = active.queueSize()
        while (active.queueSize() > HIGH_WATER_BYTES) {
            check(!disposed) { "Transport disconnected" }
            val buffered = active.queueSize()
            if (buffered < previous) progress = System.nanoTime()
            if (System.nanoTime() - progress >= TimeUnit.SECONDS.toNanos(60)) {
                fail(active, 4408, "Transport send stalled")
                error("Transport send stalled")
            }
            previous = buffered
            Thread.sleep(10)
        }
        check(!disposed && send()) { "Transport disconnected" }
    }

    @Synchronized private fun armTimeout() {
        timer?.cancel(false)
        timer = scheduler.schedule({ socket?.let { fail(it, 4408, "Fragment transfer stalled") } }, 60, TimeUnit.SECONDS)
    }

    private fun fail(active: WebSocket, code: Int, message: String) {
        if (disposed) return
        failureCode = code
        failureMessage = message
        active.close(code, message.take(100).takeWhile { it.code < 128 }.ifBlank { "Transport error" })
        downstream.onClosing(active, code, message)
        dispose()
    }

    @Synchronized fun dispose() {
        if (disposed) return
        disposed = true
        timer?.cancel(false)
        decoder.clear()
        writer.shutdownNow()
    }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { dispose(); downstream.onClosing(webSocket, code, reason) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dispose(); downstream.onClosed(webSocket, code, reason) }
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { dispose(); downstream.onFailure(webSocket, t, response) }
    companion object {
        private val scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Codex transport timeout").apply { isDaemon = true } }
    }
}
