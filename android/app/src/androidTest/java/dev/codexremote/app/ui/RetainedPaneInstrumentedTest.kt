package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RetainedPaneInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun navigationKeepsTheDocumentDraftAndViewportWithoutLeavingHiddenControls() {
        val visible = mutableStateOf(false)
        val mounted = mutableStateOf(true)
        val loads = AtomicInteger()
        val disposals = AtomicInteger()
        val browser = AtomicReference<WebView>()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                if (mounted.value) RetainedPane(visible.value) {
                    DisposableEffect(Unit) { onDispose { disposals.incrementAndGet(); browser.get()?.destroy() } }
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        WebView(context).apply {
                            browser.set(this)
                            settings.javaScriptEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) { loads.incrementAndGet() }
                            }
                            loadDataWithBaseURL("https://fixture.invalid", "<html><body><input id='draft'><script>window.identity=Math.random().toString()</script></body></html>", "text/html", "UTF-8", null)
                        }
                    }, update = { it.visibility = if (visible.value) View.VISIBLE else View.INVISIBLE })
                    Text("Chat controls")
                }
                if (!visible.value) Text("Other page")
            }
        }
        compose.runOnIdle { assertEquals(null, browser.get()); visible.value = true }
        compose.waitUntil(15_000) { loads.get() == 1 }
        assertNotNull(browser.get())
        evaluate(browser.get(), "document.getElementById('draft').value='unsent draft'")
        val before = evaluate(browser.get(), "JSON.stringify([identity,document.getElementById('draft').value,innerHeight])")
        repeat(3) {
            compose.runOnIdle { visible.value = false }
            compose.onNodeWithText("Chat controls").assertDoesNotExist()
            compose.runOnIdle { assertEquals(View.INVISIBLE, browser.get().visibility); visible.value = true }
            compose.waitForIdle()
            assertEquals(before, evaluate(browser.get(), "JSON.stringify([identity,document.getElementById('draft').value,innerHeight])"))
        }
        assertEquals(1, loads.get())
        assertEquals(0, disposals.get())
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        assertEquals(1, disposals.get())
    }

    private fun evaluate(browser: WebView, script: String): String {
        val result = AtomicReference<String>()
        compose.runOnUiThread { browser.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(5000) { result.get() != null }
        return result.get()
    }
}
