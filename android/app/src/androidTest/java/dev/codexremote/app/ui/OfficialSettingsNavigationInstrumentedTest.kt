package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OfficialSettingsNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun webViewConfirmationResumesJavaScriptOnlyAfterCancelOrLeaveAndCancelsWhenHidden() {
        val visible = mutableStateOf(true)
        val confirmation = OfficialSettingsConfirmation()
        val browser = AtomicReference<WebView>()
        val loaded = AtomicInteger()
        compose.setContent {
            DisposableEffect(Unit) { onDispose { confirmation.resolve(false); browser.get()?.destroy() } }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).apply {
                    browser.set(this)
                    settings.javaScriptEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                            confirmation.show(message, result, visible.value)
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) { loaded.incrementAndGet() }
                    }
                    loadDataWithBaseURL("https://fixture.invalid", "<html><body></body></html>", "text/html", "UTF-8", null)
                }
            })
            OfficialSettingsConfirmationDialog(confirmation, visible.value)
        }
        compose.waitUntil(15_000) { loaded.get() == 1 }
        fun request(): AtomicReference<String> {
            val result = AtomicReference<String>()
            compose.runOnUiThread { browser.get().evaluateJavascript("window.confirm('Unsaved settings')") { result.set(it) } }
            if (visible.value) compose.waitUntil(15_000) { confirmation.pending != null }
            return result
        }
        val cancelled = request()
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(10_000) { cancelled.get() != null }
        assertEquals("false", cancelled.get())
        val accepted = request()
        compose.onNodeWithText("离开", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { accepted.get() != null }
        assertEquals("true", accepted.get())
        compose.runOnIdle { visible.value = false }
        val hidden = request()
        compose.waitUntil(10_000) { hidden.get() != null }
        assertEquals("false", hidden.get())
        assertEquals(1, loaded.get())
    }
}
