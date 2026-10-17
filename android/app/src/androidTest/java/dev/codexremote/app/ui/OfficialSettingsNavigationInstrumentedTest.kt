package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OfficialSettingsNavigationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsInterceptBackOnlyWhileVisibleAndWithoutAnotherModal() {
        val visible = mutableStateOf(true)
        val inSettings = mutableStateOf(true)
        val modal = mutableStateOf(false)
        val dispatcher = AtomicReference<OnBackPressedDispatcher>()
        val exits = AtomicInteger()
        val fallbacks = AtomicInteger()
        val keyboardVisible = AtomicBoolean()
        val hideKeyboard = AtomicReference<() -> Unit>()
        compose.setContent {
            dispatcher.set(LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher)
            keyboardVisible.set(WindowInsets.ime.getBottom(LocalDensity.current) > 0)
            val keyboard = LocalSoftwareKeyboardController.current
            hideKeyboard.set { keyboard?.hide() }
            BackHandler { fallbacks.incrementAndGet() }
            OfficialSettingsBackHandler(visible.value, inSettings.value, modal.value) { exits.incrementAndGet() }
            BasicTextField("", {}, modifier = Modifier.testTag("settings-input"))
        }
        fun back() {
            compose.waitForIdle()
            compose.runOnUiThread { dispatcher.get().onBackPressed() }
            compose.waitForIdle()
        }
        back()
        assertEquals(1, exits.get())
        compose.runOnIdle { visible.value = false }
        back()
        compose.runOnIdle { visible.value = true; modal.value = true }
        back()
        compose.runOnIdle { modal.value = false; inSettings.value = false }
        back()
        assertEquals(1, exits.get())
        assertEquals(3, fallbacks.get())
        compose.runOnIdle { inSettings.value = true }
        compose.onNodeWithTag("settings-input").performClick()
        compose.waitUntil(15_000) { keyboardVisible.get() }
        back()
        assertEquals("Settings must not intercept Back while the IME is open", 1, exits.get())
        compose.runOnUiThread { hideKeyboard.get().invoke() }
        compose.waitUntil(15_000) { !keyboardVisible.get() }
        back()
        assertEquals(2, exits.get())
    }

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
