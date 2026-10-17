package dev.codexremote.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.OfficialChatSession
import dev.codexremote.app.data.OfficialFrontendStore
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OfficialNativeRequestLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun panelHasItsOwnDocumentMessagesAndStateAndClosingPreservesChat() {
        val session = session { JSONArray() }
        try {
            receive(session, snapshot("epoch-one", "initial").put("pendingHostRequests", JSONArray()))
            val mainDocument = documentId(session, "main")
            receive(session, frame(2, "surface", JSONObject().put("action", "open").put("id", "settings")
                .put("title", "Settings").put("html", "<html>settings</html>")))
            val panelDocument = documentId(session, "settings")
            session.bridge("main").setState(mainDocument, "{\"draft\":\"keep\"}")
            session.bridge("settings").setState(panelDocument, "{\"route\":\"settings\"}")
            receive(session, frame(3, "html", JSONObject().put("html", "<html>updated settings</html>"), "settings"))
            assertEquals(mainDocument, documentId(session, "main"))
            assertEquals("{\"draft\":\"keep\"}", session.bridge("main").getState(mainDocument))
            assertEquals("{\"route\":\"settings\"}", session.bridge("settings").getState(documentId(session, "settings")))
            receive(session, frame(4, "message", JSONArray().put("string").put("opaque"), "settings"))
            assertEquals(0, queueSize(session, "main"))
            assertEquals(1, queueSize(session, "settings"))
            receive(session, frame(5, "surface", JSONObject().put("action", "close").put("id", "settings")))
            assertEquals(mainDocument, documentId(session, "main"))
            assertEquals("null", session.bridge("settings").getState(panelDocument))
            assertEquals("{\"draft\":\"keep\"}", session.bridge("main").getState(mainDocument))
        } finally { instrumentation.runOnMainSync { session.close() } }
    }

    @Test
    fun closingPanelCancelsOnlyItsNativeOperation() {
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val choices = listOf(CompletableDeferred<JSONArray>(), CompletableDeferred<JSONArray>())
        val session = session {
            val index = started.getAndIncrement()
            try { choices[index].await() } finally { finished.incrementAndGet() }
        }
        try {
            receive(session, snapshot("epoch-one", "main-pick"))
            receive(session, frame(2, "surface", JSONObject().put("action", "open").put("id", "settings").put("html", "settings")))
            receive(session, frame(3, "host-request", JSONObject().put("id", "panel-pick").put("method", "showOpenDialog")
                .put("params", JSONObject()), "settings"))
            receive(session, frame(4, "surface", JSONObject().put("action", "close").put("id", "settings")))
            assertEquals(2, started.get())
            assertEquals(1, finished.get())
            instrumentation.runOnMainSync { choices.forEach { it.complete(JSONArray()) } }
            assertEquals(setOf("main-pick"), responseIds(session))
        } finally { instrumentation.runOnMainSync { session.close() } }
    }

    @Test
    fun sameHostDocumentResyncKeepsOnePendingPickerAndReusesItsResult() {
        val started = AtomicInteger()
        val selection = CompletableDeferred<JSONArray>()
        val session = session { started.incrementAndGet(); selection.await() }
        try {
            receive(session, snapshot("epoch-one", "pick-one"))
            receive(session, snapshot("epoch-one", "pick-one").put("resyncRequired", true))
            assertEquals("A document resync must not launch a second picker", 1, started.get())
            instrumentation.runOnMainSync { selection.complete(JSONArray()) }
            receive(session, snapshot("epoch-one", "pick-one"))
            assertEquals("Replayed pending request must reuse the completed response", 1, started.get())
            assertEquals(setOf("pick-one"), responseIds(session))
        } finally { instrumentation.runOnMainSync { session.close() } }
    }

    @Test
    fun newHostCancelsOldPickerAndRejectsItsLateResult() {
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val choices = listOf(CompletableDeferred<JSONArray>(), CompletableDeferred<JSONArray>())
        val session = session {
            val index = started.getAndIncrement()
            try { choices[index].await() } finally { finished.incrementAndGet() }
        }
        try {
            receive(session, snapshot("epoch-one", "old-pick"))
            receive(session, snapshot("epoch-two", "new-pick"))
            instrumentation.waitForIdleSync()
            assertEquals(2, started.get())
            assertEquals("Old host's pending native operation must be cancelled", 1, finished.get())
            instrumentation.runOnMainSync {
                choices[0].complete(JSONArray().put(JSONObject().put("path", "/tmp/old")))
                choices[1].complete(JSONArray().put(JSONObject().put("path", "/tmp/new")))
            }
            assertEquals(setOf("new-pick"), responseIds(session))
        } finally { instrumentation.runOnMainSync { session.close() } }
    }

    private fun session(pick: suspend (JSONObject) -> JSONArray): OfficialChatSession {
        val context = instrumentation.targetContext
        return OfficialChatSession(context, GatewayApi(), OfficialFrontendStore(context), "test", "test", "client", "/tmp",
            {}, {}, pick, { null }, { _, _ -> null }, { false })
    }

    private fun snapshot(epoch: String, request: String) = JSONObject().put("type", "ready")
        .put("epoch", epoch).put("viewId", "lifecycle-test").put("sequence", 1)
        .put("origin", "https://fixture.codex-remote.invalid").put("html", "<html></html>")
        .put("frames", JSONArray()).put("pendingHostRequests", JSONArray().put(JSONObject()
            .put("id", request).put("method", "showOpenDialog").put("params", JSONObject().put("canSelectFiles", true))))

    private fun receive(session: OfficialChatSession, packet: JSONObject) {
        instrumentation.runOnMainSync {
            session.javaClass.getDeclaredMethod("receive", JSONObject::class.java).apply { isAccessible = true }.invoke(session, packet)
        }
        instrumentation.waitForIdleSync()
    }

    private fun responseIds(session: OfficialChatSession): Set<String> {
        var result: Set<String> = emptySet()
        instrumentation.runOnMainSync {
            val responses = session.javaClass.getDeclaredField("hostResponses").apply { isAccessible = true }.get(session) as Map<*, *>
            result = responses.keys.map { it as String }.toSet()
        }
        return result
    }

    private fun frame(sequence: Int, type: String, payload: Any, surfaceId: String = "main") = JSONObject()
        .put("epoch", "epoch-one").put("viewId", "lifecycle-test").put("sequence", sequence)
        .put("type", type).put("payload", payload).put("surfaceId", surfaceId)

    private fun documentId(session: OfficialChatSession, id: String): String = surfaceField(session, id, "documentId") as String
    private fun queueSize(session: OfficialChatSession, id: String): Int = (surfaceField(session, id, "queue") as List<*>).size
    private fun surfaceField(session: OfficialChatSession, id: String, field: String): Any {
        var result: Any? = null
        instrumentation.runOnMainSync {
            val surfaces = session.javaClass.getDeclaredField("surfaces").apply { isAccessible = true }.get(session) as Map<*, *>
            val surface = surfaces[id]!!
            result = surface.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(surface)
        }
        return requireNotNull(result)
    }
}
