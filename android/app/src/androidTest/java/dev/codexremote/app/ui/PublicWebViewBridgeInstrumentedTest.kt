package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Uses an ordinary inline page, without importing or patching any official frontend module. */
class PublicWebViewBridgeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun publicApiExistsBeforePageScriptsAndPersistsOpaqueState() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        val received = AtomicReference<String>()
        val stored = AtomicReference<String>()
        val browser = AtomicReference<WebView>()
        val bridge = object {
            @JavascriptInterface fun getState(documentId: String): String = "{\"opaque\":\"draft\"}"
            @JavascriptInterface fun setState(documentId: String, state: String) { stored.set(state) }
            @JavascriptInterface fun postMessage(documentId: String, message: String) { received.set(message) }
            @JavascriptInterface fun ready(documentId: String) {}
        }
        compose.setContent {
            DisposableEffect(Unit) { onDispose { browser.get()?.destroy() } }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    browser.set(this)
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(bridge, "CodexExtensionBridge")
                    val script = context.assets.open("bridge.js").bufferedReader().use { it.readText() }
                    val theme = context.assets.open("theme.css").bufferedReader().use { it.readText() }
                    val config = JSONObject().put("documentId", "test-page").put("theme", "light").put("fontScale", 1).put("themeCss", theme)
                    WebViewCompat.addDocumentStartJavaScript(this, "window.__codexExtensionConfig = $config;\n$script", setOf("https://fixture.codex-remote.invalid"))
                    loadDataWithBaseURL("https://fixture.codex-remote.invalid/", """
                        <!doctype html><html><head><script>
                        const api = acquireVsCodeApi();
                        api.setState({ untouched: api.getState(), bytes: [0, 255] });
                        api.postMessage(new Uint8Array([0, 128, 255]));
                        </script></head><body>Public host API</body></html>
                    """.trimIndent(), "text/html", "UTF-8", null)
                }
            })
        }
        compose.waitUntil(15_000) { received.get() != null && stored.get() != null }
        assertEquals("bytes", JSONArray(received.get()).getString(0))
        assertEquals("AID/", JSONArray(received.get()).getString(1))
        assertEquals("draft", JSONObject(stored.get()).getJSONObject("untouched").getString("opaque"))
    }
}
