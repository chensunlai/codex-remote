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
    private inner class Fixture : Closeable {
        val server = SocketFixture()
        val document = AtomicReference<String>()
        val loads = AtomicInteger()
        val statuses = CopyOnWriteArrayList<String?>()
        private lateinit var browser: WebView
        private lateinit var session: OfficialChatSession

        init {
            instrumentation.runOnMainSync {
                val context = instrumentation.targetContext
                val api = GatewayApi().apply { configure(GatewayConfig("http://127.0.0.1:${server.port}", "fixture-token")) }
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
            instrumentation.runOnMainSync { session.close(); browser.destroy() }
            server.close()
        }
    }

    private class SocketFixture : Closeable {
        private val listener = ServerSocket(0)
        val port: Int get() = listener.localPort
        val messages = CopyOnWriteArrayList<JSONObject>()
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
            @Volatile var autoDelivery = true
            @Volatile private var sentClose = false

            fun serve() {
                try {
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        if (byte < 0) return
                        headers.append(byte.toChar())
                    }
                    val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    synchronized(output) {
                        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                        output.flush()
                    }
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
                        require(length in 0..2L * 1024 * 1024)
                        val mask = if (size and 128 != 0) readExactly(4) else null
                        val bytes = readExactly(length.toInt())
                        if (mask != null) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
                        when (opcode and 15) {
                            1 -> {
                                val packet = JSONObject(String(bytes, Charsets.UTF_8))
                                packets += packet
                                if (packet.optString("type") == "message") {
                                    messages += packet
                                    if (autoDelivery) delivery(packet)
                                }
                            }
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
            fun send(packet: JSONObject) = frame(1, packet.toString().toByteArray())
            fun closeFromServer() {
                sentClose = true
                frame(8, byteArrayOf(3, 0xF4.toByte())) // 1012: service restart/reconnect
            }
            fun abort() { socket.close() }

            private fun frame(opcode: Int, bytes: ByteArray) = synchronized(output) {
                output.write(128 or opcode)
                if (bytes.size < 126) output.write(bytes.size)
                else {
                    require(bytes.size <= 65535)
                    output.write(126)
                    output.write(bytes.size shr 8)
                    output.write(bytes.size and 255)
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

        fun eventually(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (!condition()) {
                check(System.nanoTime() < deadline) { "Timed out waiting for fixture outcome" }
                Thread.sleep(20)
            }
        }
    }
}
