package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentCallbacks2
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.OfficialChatReconnectInstrumentedTest.SocketFixture
import dev.codexremote.app.data.OfficialChatSession
import dev.codexremote.app.data.OfficialFrontendStore
import dev.codexremote.app.model.GatewayConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RetainedServerPanesInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableStateOf<String?>("a")
    private val available = mutableStateOf(setOf("a", "b", "c", "d"))
    private val visible = mutableStateOf(true)
    private val foreground = mutableStateOf(true)
    private val identity = mutableStateOf("gateway")
    private val mounted = mutableStateOf(true)
    private val browsers = ConcurrentHashMap<String, WebView>()
    private val loads = ConcurrentHashMap<String, AtomicInteger>()
    private val disposals = ConcurrentHashMap<String, AtomicInteger>()

    @SuppressLint("SetJavaScriptEnabled")
    private fun mount(api: GatewayApi) {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                if (mounted.value) RetainedServerPanes(identity.value, available.value, selected.value, visible.value) { id, active ->
                    val context = LocalContext.current
                    val ownedBrowser = remember { AtomicReference<WebView>() }
                    val session = remember {
                        OfficialChatSession(context.applicationContext, api, OfficialFrontendStore(context), id,
                            "retained-test-${UUID.randomUUID()}", id, "/tmp", {}, {}, { JSONArray() }, { null }, { _, _ -> null }, { false })
                    }
                    val interactive = active && foreground.value
                    LaunchedEffect(interactive) { session.setVisible(interactive) }
                    DisposableEffect(session) {
                        onDispose {
                            session.close()
                            ownedBrowser.get()?.let { browser ->
                                browsers.remove(id, browser)
                                browser.destroy()
                            }
                            disposals.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                        }
                    }
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                        WebView(context).apply {
                            ownedBrowser.set(this)
                            browsers[id] = this
                            settings.javaScriptEnabled = true
                            addJavascriptInterface(session.bridge("main"), "CodexFixture")
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) { loads.getOrPut(id) { AtomicInteger() }.incrementAndGet() }
                            }
                            session.attach(this) { origin, html, token ->
                                loadDataWithBaseURL("$origin/", html.replace("DOCUMENT_TOKEN", JSONObject.quote(token)), "text/html", "UTF-8", null)
                            }
                        }
                    }, update = { it.visibility = if (interactive) View.VISIBLE else View.INVISIBLE })
                    Text("Controls $id")
                }
            }
        }
    }

    private fun connect(server: SocketFixture, id: String): SocketFixture.Connection {
        val previous = loads[id]?.get() ?: 0
        change { selected.value = id }
        val connection = server.nextConnection()
        connection.ready()
        compose.waitUntil(15_000) { (loads[id]?.get() ?: 0) > previous }
        connection.awaitVisibility(true)
        return connection
    }

    private fun change(action: () -> Unit) {
        compose.runOnIdle(action)
        compose.waitForIdle()
    }

    private fun evaluate(id: String, script: String): String {
        val result = AtomicReference<String>()
        compose.runOnUiThread { browsers.getValue(id).evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(5000) { result.get() != null }
        return result.get()
    }

    private fun fixture(block: (SocketFixture, GatewayApi) -> Unit) {
        SocketFixture().use { server ->
            val api = GatewayApi().apply { configure(GatewayConfig("http://127.0.0.1:${server.port}", "fixture", allowHttp = true)) }
            try { block(server, api) }
            finally {
                change { mounted.value = false }
                compose.waitForIdle()
                api.clear()
            }
        }
    }

    @Test fun switchingServersTabsAndBackgroundPreservesDraftsSocketsAndHiddenUpdates() = fixture { server, api ->
        mount(api)
        val a = connect(server, "a")
        evaluate("a", "document.getElementById('draft').value='未发送 图片🙂'; document.body.style.height='5000px'; scrollTo(0, 400)")
        val original = evaluate("a", "JSON.stringify([identity,document.getElementById('draft').value,scrollY])")
        val b = connect(server, "b")
        a.awaitVisibility(false)
        compose.onNodeWithText("Controls a").assertDoesNotExist()
        a.message(1, "hidden update")
        compose.waitUntil(5000) { evaluate("a", "received[0]") == "\"hidden update\"" }
        change { selected.value = "a" }
        a.awaitVisibility(true)
        b.awaitVisibility(false)
        assertEquals(original, evaluate("a", "JSON.stringify([identity,document.getElementById('draft').value,scrollY])"))
        change { visible.value = false }
        a.awaitVisibility(false)
        compose.onNodeWithText("Controls a").assertDoesNotExist()
        change { visible.value = true; foreground.value = false }
        compose.waitForIdle()
        change { assertEquals(View.INVISIBLE, browsers.getValue("a").visibility) }
        change { foreground.value = true }
        a.awaitVisibility(true)
        assertEquals(original, evaluate("a", "JSON.stringify([identity,document.getElementById('draft').value,scrollY])"))
        assertEquals(2, server.requests.size)
        assertEquals(1, loads.getValue("a").get())
        assertEquals(1, loads.getValue("b").get())
        assertTrue(disposals.isEmpty())
        assertTrue(server.messages.isEmpty())
        assertNull(server.pollConnection(300))
    }

    @Test fun lruMemoryPressureRemovalAndGatewayChangeReleaseOnlyTheAppropriatePages() = fixture { server, api ->
        mount(api)
        val a = connect(server, "a")
        val b = connect(server, "b")
        change { selected.value = "a" }
        a.awaitVisibility(true)
        val c = connect(server, "c")
        connect(server, "d")
        assertTrue(b.closeReceived.await(5, TimeUnit.SECONDS))
        assertEquals(setOf("a", "c", "d"), browsers.keys.toSet())
        assertEquals(1, disposals.getValue("b").get())
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        change { application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
        compose.waitForIdle()
        assertEquals(setOf("a", "c", "d"), browsers.keys.toSet())
        change { application.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        compose.waitForIdle()
        assertEquals(setOf("d"), browsers.keys.toSet())
        assertTrue(a.closeReceived.await(5, TimeUnit.SECONDS))
        assertTrue(c.closeReceived.await(5, TimeUnit.SECONDS))
        connect(server, "b")
        change { available.value = setOf("a", "b", "c") }
        compose.waitForIdle()
        assertEquals(setOf("b"), browsers.keys.toSet())
        change { identity.value = "other-gateway" }
        val replacement = server.nextConnection()
        replacement.ready()
        compose.waitUntil(15_000) { loads.getValue("b").get() == 3 }
        assertEquals(2, disposals.getValue("b").get())
        assertTrue(server.messages.isEmpty())
    }
}
