package dev.codexremote.app.data

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.model.GatewayConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real local WebSockets and an ordinary fixture WebView; no official plugin internals are used. */
@RunWith(AndroidJUnit4::class)
class OfficialChatReconnectInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun manualGatewayReconnectPreservesTheDocumentAndUnsentDraft() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.evaluate("document.getElementById('draft').value='unsent 图片🙂'")
            val before = fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])")
            kotlinx.coroutines.runBlocking { fixture.api.checkConnection(preferPrimary = true, reconnect = true) }
            val second = fixture.server.nextConnection()
            second.ready()
            second.awaitPacket("visibility")
            assertEquals(before, fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])"))
            assertEquals(1, fixture.loads.get())
            assertEquals(0, fixture.server.messages.size)
        }
    }

    @Test fun changingExperimentsDuringHandshakeDoesNotLeaveTheIndicatorSpinning() {
        val gate = CountDownLatch(1)
        Fixture(handshakeGate = gate).use { fixture ->
            fixture.api.updatePolicy(GatewayConfig("http://127.0.0.1:${fixture.server.port}", "fixture-token",
                allowHttp = true, multipleEndpoints = true))
            gate.countDown()
            fixture.start()
            eventually { !fixture.api.link.value.checking }
            assertNotEquals(null, fixture.api.link.value.latencyMs)
        }
    }

    @Test fun manualReconnectCanRestartAStoppedTransport() {
        Fixture().use { fixture ->
            val first = fixture.start()
            first.oversizedMessage()
            eventually { fixture.statuses.any { it?.contains("传输已停止") == true } }
            kotlinx.coroutines.runBlocking { fixture.api.checkConnection(reconnect = true) }
            val second = fixture.server.nextConnection()
            second.ready()
            second.awaitPacket("visibility")
            second.message(1, "after manual retry")
            eventually { fixture.evaluate("received[0]") == "\"after manual retry\"" }
            assertEquals(1, fixture.loads.get())
        }
    }

    @Test fun switchingGatewayAddressPreservesRendererRecoveryIdentityAndDraft() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.evaluate("document.getElementById('draft').value='keep across endpoints'")
            val before = fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])")
            val alternative = "http://localhost:${fixture.server.port}"
            fixture.api.updatePolicy(GatewayConfig("http://127.0.0.1:${fixture.server.port}", "fixture-token",
                allowHttp = true, multipleEndpoints = true, endpoints = listOf(alternative)))
            kotlinx.coroutines.runBlocking { fixture.api.checkConnection(preferPrimary = true) }
            val second = fixture.server.nextConnection()
            second.ready()
            second.awaitPacket("visibility")
            assertEquals(alternative, fixture.api.link.value.endpoint)
            assertEquals(before, fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])"))
            assertEquals(1, fixture.loads.get())
            assertEquals(0, fixture.server.messages.size)
            assertEquals(1, fixture.server.requests.map { android.net.Uri.parse(it).getQueryParameter("rendererId") }.distinct().size)
        }
    }

    @Test fun standardCompressionPreservesFragmentedMessagesInBothDirections() {
        Fixture(compression = true).use { fixture ->
            val first = fixture.start()
            val document = fixture.document.get()
            first.generatedMessage(1, 34 * 1024 * 1024)
            eventually(30_000) { fixture.evaluate("received[0]?.length") == (34 * 1024 * 1024).toString() }
            val text = "图🙂".repeat(1_300_000)
            fixture.post(text)
            eventually { fixture.server.messages.size == 1 }
            assertEquals(text, fixture.server.messages.single().getJSONArray("payload").getString(1))
            assertTrue(first.compressedSent.get() >= 2)
            assertTrue(first.compressedReceived.get() >= 2)
            assertEquals(document, fixture.document.get())
            assertEquals(1, fixture.loads.get())
        }
    }

    @Test fun largeConversationSnapshotReachesWebViewWithoutReloadingOrLosingDraft() {
        Fixture().use { fixture ->
            val first = fixture.start()
            fixture.evaluate("document.getElementById('draft').value='keep unsent draft'")
            val before = fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])")
            first.message(1, "x".repeat(20 * 1024 * 1024))
            eventually { fixture.evaluate("received[0]?.length") == (20 * 1024 * 1024).toString() }
            first.message(2, "next update")
            eventually { fixture.evaluate("received[1]") == "\"next update\"" }
            assertEquals(before, fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])"))
            assertEquals(1, fixture.loads.get())
            assertEquals(null, fixture.server.pollConnection(500))
            assertEquals(0, fixture.server.messages.size)
        }
    }

    @Test fun snapshotAbove64MiBReachesWebViewAsOneMessage() {
        Fixture().use { fixture ->
            val first = fixture.start()
            val document = fixture.document.get()
            first.generatedMessage(1, 70 * 1024 * 1024)
            eventually(30_000) { fixture.evaluate("received[0]?.length") == (70 * 1024 * 1024).toString() }
            first.message(2, "next")
            eventually { fixture.evaluate("received[1]") == "\"next\"" }
            assertEquals(document, fixture.document.get())
            assertEquals(1, fixture.loads.get())
            assertEquals(null, fixture.server.pollConnection(500))
        }
    }

    @Test fun interruptedFragmentDoesNotAcknowledgeOrReplaceTheDocument() {
        Fixture().use { fixture ->
            val first = fixture.start()
            val before = fixture.document.get()
            first.partialMessage()
            Thread.sleep(200)
            assertEquals("0", fixture.evaluate("received.length"))
            first.abort()
            val second = fixture.server.nextConnection()
            second.ready()
            second.message(1, "complete after reconnect")
            eventually { fixture.evaluate("received[0]") == "\"complete after reconnect\"" }
            assertEquals(before, fixture.document.get())
            assertEquals(1, fixture.loads.get())
        }
    }

    @Test fun largeOutboundMessageIsFragmentedAndDeliveredOnlyOnce() {
        Fixture().use { fixture ->
            val first = fixture.start()
            val text = "图🙂".repeat(1_300_000)
            fixture.post(text)
            eventually { fixture.server.messages.size == 1 }
            assertEquals(text, fixture.server.messages.single().getJSONArray("payload").getString(1))
            first.message(1, "after upload")
            eventually { fixture.evaluate("received[0]") == "\"after upload\"" }
            assertEquals(1, fixture.loads.get())
        }
    }

    @Test fun oversizedTransferStopsWithoutAnAutomaticReconnectLoop() {
        Fixture().use { fixture ->
            val first = fixture.start()
            first.oversizedMessage()
            eventually { fixture.statuses.any { it?.contains("传输已停止") == true } }
            assertEquals(null, fixture.server.pollConnection(2_000))
            assertEquals("0", fixture.evaluate("received.length"))
            assertEquals(1, fixture.loads.get())
        }
    }

    @Test fun serverCloseReconnectsPromptlyWithoutReloadingTheDocumentOrLosingTheDraft() {
        Fixture().use { fixture ->
            val first = fixture.start()
            fixture.evaluate("document.getElementById('draft').value='keep unsent draft'")
            val before = fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])")
            first.closeFromServer()
            assertTrue("The client must complete the server's close handshake", first.closeReceived.await(3, TimeUnit.SECONDS))
            val second = fixture.server.nextConnection()
            second.ready()
            second.awaitPacket("visibility")
            instrumentation.waitForIdleSync()
            assertEquals(before, fixture.evaluate("JSON.stringify([identity,document.getElementById('draft').value])"))
            assertEquals(1, fixture.loads.get())
            assertEquals("A close callback must schedule only one reconnect", null, fixture.server.pollConnection(700))
        }
    }

    @Test fun actionsCreatedDuringDisconnectionWaitForReadyAndAreDeliveredExactlyOnce() {
        Fixture().use { fixture ->
            val first = fixture.start()
            first.closeFromServer()
            val second = fixture.server.nextConnection()
            val document = fixture.document.get()
            fixture.post("created-offline", document)
            instrumentation.waitForIdleSync()
            assertEquals(0, fixture.server.messages.size)
            assertEquals(1, fixture.loads.get())
            second.ready()
            eventually { fixture.server.messages.size == 1 }
            assertEquals("created-offline", fixture.server.messages.single().getJSONArray("payload").getString(1))
            // A later frame proves the preceding delivery acknowledgement was processed.
            second.message(1, "after-delivery")
            eventually { fixture.evaluate("received.includes('after-delivery')") == "true" }
            second.closeFromServer()
            val third = fixture.server.nextConnection()
            third.ready(sequence = 1)
            third.awaitPacket("visibility")
            instrumentation.waitForIdleSync()
            assertEquals(document, fixture.document.get())
            assertEquals(1, fixture.loads.get())
            assertEquals("An already delivered action must not be replayed", 1, fixture.server.messages.size)
        }
    }

    @Test fun queuedActionsAndLateCallbacksFromAnOldHostDocumentAreDiscarded() {
        Fixture().use { fixture ->
            val first = fixture.start()
            val oldDocument = fixture.document.get()
            first.closeFromServer()
            val second = fixture.server.nextConnection()
            fixture.post("obsolete-offline", oldDocument)
            instrumentation.waitForIdleSync()
            second.ready(epoch = "replacement-host")
            eventually { fixture.loads.get() == 2 }
            assertNotEquals(oldDocument, fixture.document.get())
            fixture.post("late-old-document", oldDocument)
            fixture.post("current-document")
            eventually { fixture.server.messages.isNotEmpty() }
            assertEquals(listOf("current-document"), fixture.server.messages.map { it.getJSONArray("payload").getString(1) })
        }
    }

    @Test fun actionsWithUnknownDeliveryOutcomeAreNotReplayedOnReconnect() {
        Fixture().use { fixture ->
            val first = fixture.start()
            first.autoDelivery = false
            fixture.post("possibly-executed")
            eventually { fixture.server.messages.size == 1 }
            first.abort()
            val second = fixture.server.nextConnection()
            second.ready()
            eventually { fixture.loads.get() == 2 }
            second.awaitPacket("visibility")
            instrumentation.waitForIdleSync()
            assertEquals(1, fixture.server.messages.size)
            assertEquals("possibly-executed", fixture.server.messages.single().getJSONArray("payload").getString(1))
        }
    }

    @Test fun recoverableExtensionErrorsKeepTheConnectionAndDocument() {
        Fixture().use { fixture ->
            val first = fixture.start()
            val before = fixture.evaluate("identity")
            first.send(JSONObject().put("type", "status").put("viewId", VIEW_ID).put("epoch", EPOCH).put("sequence", 1)
                .put("payload", JSONObject().put("state", "error").put("retryable", true).put("reloadRequired", false)
                    .put("message", "recoverable-fixture-error")))
            eventually { fixture.statuses.contains("recoverable-fixture-error") }
            instrumentation.waitForIdleSync()
            assertEquals(null, fixture.server.pollConnection(1_000))
            assertFalse("A recoverable operation failure must not close the socket", first.closeReceived.await(100, TimeUnit.MILLISECONDS))
            assertEquals(before, fixture.evaluate("identity"))
            assertEquals(1, fixture.loads.get())
        }
    }

    @Test fun explicitHostReloadStillRecreatesTheDocument() {
        Fixture().use { fixture ->
            val first = fixture.start()
            val oldDocument = fixture.document.get()
            first.send(JSONObject().put("type", "status").put("viewId", VIEW_ID).put("epoch", EPOCH).put("sequence", 1)
                .put("payload", JSONObject().put("state", "error").put("retryable", false).put("reloadRequired", true)
                    .put("message", "host-reload-fixture")))
            val second = fixture.server.nextConnection()
            second.ready()
            eventually { fixture.loads.get() == 2 }
            assertNotEquals(oldDocument, fixture.document.get())
        }
    }

    @Test fun delayedDeliveryAcknowledgementsApplyBackpressureWithoutReloadingThePage() {
        Fixture().use { fixture ->
            val first = fixture.start()
            first.autoDelivery = false
            repeat(260) { fixture.post("queued-$it") }
            eventually { fixture.server.messages.size >= 256 }
            assertEquals("The delivery window must limit outstanding actions", 256, fixture.server.messages.size)
            assertEquals(1, fixture.loads.get())
            assertEquals(null, fixture.server.pollConnection(500))
            first.autoDelivery = true
            fixture.server.messages.toList().forEach(first::delivery)
            eventually { fixture.server.messages.size == 260 }
            assertEquals(260, fixture.server.messages.map { it.getString("messageId") }.toSet().size)
            assertEquals((0 until 260).map { "queued-$it" }, fixture.server.messages.map { it.getJSONArray("payload").getString(1) })
            assertEquals(1, fixture.loads.get())
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private inner class Fixture(compression: Boolean = false, handshakeGate: CountDownLatch? = null) : Closeable {
        val server = SocketFixture(compression, handshakeGate)
        val document = AtomicReference<String>()
        val loads = AtomicInteger()
        val statuses = CopyOnWriteArrayList<String?>()
        private lateinit var browser: WebView
        private lateinit var session: OfficialChatSession
        val api = GatewayApi()

        init {
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                api.configure(GatewayConfig("http://127.0.0.1:${server.port}", "fixture-token", allowHttp = true))
                val scope = "reconnect-instrumented-${UUID.randomUUID()}"
                session = OfficialChatSession(context, api, OfficialFrontendStore(context), "service", scope, "client", "/tmp",
                    { statuses += it }, {}, { JSONArray() }, { null }, { _, _ -> null }, { false })
                browser = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(session.bridge("main"), "CodexFixture")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loads.incrementAndGet() }
                    }
                }
                session.attach(browser) { origin, html, id ->
                    document.set(id)
                    browser.loadDataWithBaseURL("$origin/", html.replace("DOCUMENT_TOKEN", JSONObject.quote(id)), "text/html", "UTF-8", null)
                }
            }
        }

        fun start(): SocketFixture.Connection = server.nextConnection().also {
            it.ready()
            eventually { loads.get() == 1 }
            assertEquals("true", evaluate("typeof identity === 'string'"))
        }

        fun post(value: String, id: String = document.get()) {
            session.bridge("main").postMessage(id, JSONArray().put("string").put(value).toString())
        }

        fun evaluate(script: String): String {
            val value = AtomicReference<String>()
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value.set(it); done.countDown() } }
            assertTrue("WebView did not evaluate fixture script", done.await(5, TimeUnit.SECONDS))
            return value.get()
        }

        override fun close() {
            instrumentation.runOnMainSync { session.close(); api.clear(); browser.destroy() }
            server.close()
        }
    }

    internal class SocketFixture(private val compression: Boolean = false, private val handshakeGate: CountDownLatch? = null) : Closeable {
        private val listener = ServerSocket(0)
        val port: Int get() = listener.localPort
        val messages = CopyOnWriteArrayList<JSONObject>()
        val requests = CopyOnWriteArrayList<String>()
        private val accepted = LinkedBlockingQueue<Connection>()
        private val connections = CopyOnWriteArrayList<Connection>()
        @Volatile private var stopped = false

        init {
            thread(isDaemon = true, name = "official-reconnect-accept") {
                while (!stopped) {
                    val socket = try { listener.accept() } catch (_: Exception) { break }
                    val connection = Connection(socket)
                    connections += connection
                    thread(isDaemon = true, name = "official-reconnect-peer") { connection.serve() }
                }
            }
        }

        fun nextConnection(): Connection = requireNotNull(pollConnection(10_000)) { "App did not establish/reestablish the WebSocket" }
        fun pollConnection(timeoutMillis: Long): Connection? = accepted.poll(timeoutMillis, TimeUnit.MILLISECONDS)

        inner class Connection(private val socket: Socket) {
            private val input = BufferedInputStream(socket.getInputStream())
            private val output = socket.getOutputStream()
            private val packets = CopyOnWriteArrayList<JSONObject>()
            val closeReceived = CountDownLatch(1)
            val compressedReceived = AtomicInteger()
            val compressedSent = AtomicInteger()
            @Volatile var autoDelivery = true
            private val fragments = FragmentDecoder(128 * 1024 * 1024)
            private var transferId = 0
            @Volatile private var sentClose = false

            fun serve() {
                try {
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte < 0) return
                        headers.append(byte.toChar())
                    }
                    if (headers.startsWith("GET /api/v1/meta ")) {
                        val body = "{\"data\":{\"version\":\"fixture\"}}".toByteArray()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                        output.flush()
                        return
                    }
                    check(handshakeGate?.await(5, TimeUnit.SECONDS) != false)
                    requests += headers.lineSequence().first().split(" ")[1]
                    val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    if (compression) check(headers.contains("permessage-deflate"))
                    val extension = if (compression) "Sec-WebSocket-Extensions: permessage-deflate; client_no_context_takeover; server_no_context_takeover\r\n" else ""
                    synchronized(output) {
                        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n${extension}\r\n").toByteArray())
                        output.flush()
                    }
                    frame(1, JSONObject().put("type", "transport-config").put("protocol", "crw1")
                        .put("chunkBytes", TRANSPORT_CHUNK_BYTES).put("maxMessageBytes", 128 * 1024 * 1024).toString().toByteArray())
                    accepted.put(this)
                    while (!socket.isClosed) {
                        val opcode = input.read()
                        if (opcode < 0) break
                        val size = input.read()
                        if (size < 0) break
                        var length = (size and 127).toLong()
                        if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
                        else if (length == 127L) {
                            length = 0
                            repeat(8) { length = (length shl 8) or input.read().toLong() }
                        }
                        require(length in 0..TRANSPORT_UPLOAD_BYTES.toLong())
                        val mask = if (size and 128 != 0) readExactly(4) else null
                        var bytes = readExactly(length.toInt())
                        if (mask != null) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
                        if (opcode and 64 != 0) {
                            check(compression)
                            bytes = inflate(bytes)
                            compressedReceived.incrementAndGet()
                        }
                        when (opcode and 15) {
                            1 -> {
                                acceptPacket(JSONObject(String(bytes, Charsets.UTF_8)))
                            }
                            2 -> fragments.binary(bytes)?.let { acceptPacket(JSONObject(it)) }
                            8 -> { closeReceived.countDown(); if (!sentClose) frame(8, bytes); break }
                            9 -> frame(10, bytes)
                        }
                    }
                } catch (_: Exception) {
                    // Tests deliberately drop connections before the WebSocket close handshake.
                } finally { socket.close() }
            }

            fun ready(epoch: String = EPOCH, sequence: Int = 0) = send(JSONObject().put("type", "ready").put("viewId", VIEW_ID)
                .put("epoch", epoch).put("sequence", sequence).put("version", "fixture-1").put("packageSha256", "a".repeat(64))
                .put("origin", "https://fixture.codex-remote.invalid").put("html", HTML)
                .put("frames", JSONArray()).put("surfaces", JSONArray()).put("pendingHostRequests", JSONArray()))

            fun message(sequence: Int, value: String) = send(JSONObject().put("type", "message").put("viewId", VIEW_ID)
                .put("epoch", EPOCH).put("sequence", sequence).put("surfaceId", "main")
                .put("payload", JSONArray().put("string").put(value)))

            fun delivery(packet: JSONObject) = send(JSONObject().put("type", "delivery")
                .put("messageId", packet.getString("messageId")).put("accepted", true))

            fun awaitPacket(type: String) = eventually { packets.any { it.optString("type") == type } }
            fun awaitVisibility(visible: Boolean) = eventually {
                packets.lastOrNull { it.optString("type") == "visibility" }?.optBoolean("visible") == visible
            }
            private fun acceptPacket(packet: JSONObject) {
                packets += packet
                if (packet.optString("type") == "message") {
                    messages += packet
                    if (autoDelivery) delivery(packet)
                }
            }
            @Synchronized fun send(packet: JSONObject) {
                awaitPacket("transport-ready")
                val bytes = packet.toString().toByteArray()
                if (bytes.size <= TRANSPORT_WIRE_BYTES) frame(1, bytes)
                else {
                    val id = ++transferId
                    var offset = 0
                    while (offset < bytes.size) {
                        val length = minOf(TRANSPORT_CHUNK_BYTES, bytes.size - offset)
                        val part = java.nio.ByteBuffer.allocate(16 + length).putInt(0x43525731).putInt(id)
                            .putInt(offset).putInt(bytes.size).put(bytes, offset, length).array()
                        frame(2, part)
                        offset += length
                    }
                }
            }
            @Synchronized fun generatedMessage(sequence: Int, size: Int) {
                awaitPacket("transport-ready")
                val prefix = """{"type":"message","viewId":"$VIEW_ID","epoch":"$EPOCH","sequence":$sequence,"surfaceId":"main","payload":["string","""".toByteArray()
                val suffix = "\"]}".toByteArray()
                val total = prefix.size + size + suffix.size
                val id = ++transferId
                var offset = 0
                while (offset < total) {
                    val count = minOf(TRANSPORT_CHUNK_BYTES, total - offset)
                    val part = ByteArray(count + 16) { 'x'.code.toByte() }
                    java.nio.ByteBuffer.wrap(part).putInt(0x43525731).putInt(id).putInt(offset).putInt(total)
                    if (offset < prefix.size) prefix.copyInto(part, 16, offset, minOf(prefix.size, offset + count))
                    val suffixStart = maxOf(offset, prefix.size + size)
                    if (suffixStart < offset + count) suffix.copyInto(part, 16 + suffixStart - offset, suffixStart - prefix.size - size, offset + count - prefix.size - size)
                    frame(2, part)
                    offset += count
                }
            }
            fun oversizedMessage() {
                frame(2, java.nio.ByteBuffer.allocate(17).putInt(0x43525731).putInt(999).putInt(0)
                    .putInt(129 * 1024 * 1024).put(1.toByte()).array())
            }
            fun partialMessage() {
                val bytes = java.nio.ByteBuffer.allocate(16 + TRANSPORT_CHUNK_BYTES).putInt(0x43525731)
                    .putInt(999).putInt(0).putInt(40 * 1024 * 1024).array()
                frame(2, bytes)
            }
            fun closeFromServer() {
                sentClose = true
                frame(8, byteArrayOf(3, 0xF4.toByte())) // 1012: service restart/reconnect
            }
            fun abort() { socket.close() }

            private fun frame(opcode: Int, original: ByteArray) = synchronized(output) {
                val compressed = compression && opcode in 1..2 && original.size >= 1024
                val bytes = if (compressed) deflate(original) else original
                if (compressed) compressedSent.incrementAndGet()
                output.write(128 or opcode or if (compressed) 64 else 0)
                if (bytes.size < 126) output.write(bytes.size)
                else if (bytes.size <= 65535) {
                    output.write(126)
                    output.write(bytes.size shr 8)
                    output.write(bytes.size and 255)
                } else {
                    output.write(127)
                    for (shift in 56 downTo 0 step 8) output.write(((bytes.size.toLong() ushr shift) and 255).toInt())
                }
                output.write(bytes)
                output.flush()
            }

            private fun readExactly(length: Int): ByteArray = ByteArray(length).also { bytes ->
                var read = 0
                while (read < length) {
                    val count = input.read(bytes, read, length - read)
                    check(count > 0)
                    read += count
                }
            }
        }

        override fun close() { stopped = true; listener.close(); connections.forEach(Connection::abort) }
    }

    private companion object {
        const val EPOCH = "original-host"
        const val VIEW_ID = "reconnect-fixture"
        const val HTML = """<!doctype html><html><head><script>
            window.identity=Math.random().toString(); window.received=[];
            window.__codexExtensionHost={receive:function(value){window.received.push(value[1]);}};
            window.CodexFixture.ready(DOCUMENT_TOKEN);
            </script></head><body><input id="draft"></body></html>"""

        // RFC 7692 raw DEFLATE messages omit the trailing SYNC_FLUSH marker.
        fun deflate(bytes: ByteArray): ByteArray {
            val codec = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
            try {
                codec.setInput(bytes)
                val output = java.io.ByteArrayOutputStream()
                val scratch = ByteArray(8192)
                while (true) {
                    val count = codec.deflate(scratch, 0, scratch.size, java.util.zip.Deflater.SYNC_FLUSH)
                    output.write(scratch, 0, count)
                    if (count < scratch.size) break
                }
                return output.toByteArray().let { it.copyOf(it.size - 4) }
            } finally { codec.end() }
        }

        fun inflate(bytes: ByteArray): ByteArray {
            val codec = java.util.zip.Inflater(true)
            try {
                codec.setInput(bytes + byteArrayOf(0, 0, -1, -1))
                val output = java.io.ByteArrayOutputStream()
                val scratch = ByteArray(8192)
                while (!codec.needsInput()) {
                    val count = codec.inflate(scratch)
                    check(count > 0 || codec.needsInput())
                    output.write(scratch, 0, count)
                    check(output.size() <= TRANSPORT_WIRE_BYTES)
                }
                return output.toByteArray()
            } finally { codec.end() }
        }

        fun eventually(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for fixture outcome" }
                Thread.sleep(20)
            }
        }
    }
}
