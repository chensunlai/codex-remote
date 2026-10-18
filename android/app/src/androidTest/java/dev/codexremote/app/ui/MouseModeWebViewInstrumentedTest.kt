package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/** The production host bridge and a browser fixture; official frontend bytes stay untouched. */
class MouseModeWebViewInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val host = AtomicReference<MouseModeWebViewContainer>()
    private val loads = AtomicInteger()

    @Test
    fun mediaGatedAttachmentCanBeRemovedWithATrackpadTapWithoutFocusNavigation() {
        showFixture()
        assertEquals("false", evaluate("matchMedia('(hover: hover)').matches"))
        val identity = evaluate("window.identity")
        evaluate("document.getElementById('draft').value = 'keep this unsent'; document.getElementById('remove').addEventListener('pointerdown', event => { event.preventDefault(); event.stopPropagation(); })")
        setMouseMode(true)
        val distance = dragDistance()

        drag(distance, 0f)
        waitForScript("getComputedStyle(document.getElementById('remove')).opacity === '1'")
        assertEquals("0", evaluate("window.clicks"))
        drag(-distance, 0f)
        tap()

        waitForScript("window.deleted")
        assertEquals("1", evaluate("window.clicks"))
        assertTrue(evaluate("window.lastPointerType") in setOf("\"mouse\"", "\"touch\""))
        assertEquals("true", evaluate("window.lastClickTrusted"))
        assertEquals("\"keep this unsent\"", evaluate("document.getElementById('draft').value"))
        assertEquals("0", evaluate("window.sends"))
        assertEquals(identity, evaluate("window.identity"))
        assertEquals("false", evaluate("matchMedia('(hover: hover)').matches"))
        assertEquals(1, loads.get())
    }

    @Test
    fun focusNavigationAndPointerTapRemoveAMediaGatedButtonWithoutSendingTheDraft() {
        showFixture()
        evaluate("document.getElementById('draft').value = 'keep this unsent'; document.getElementById('remove').addEventListener('pointerdown', event => { event.preventDefault(); event.stopPropagation(); })")
        setMouseMode(true)
        compose.runOnUiThread { host.get().navigateFocus(forward = true) }
        waitForScript("document.activeElement.id === 'remove'")
        compose.runOnUiThread { host.get().navigateFocus(forward = true) }
        waitForScript("document.activeElement.id === 'plain'")
        compose.runOnUiThread { host.get().navigateFocus(forward = false) }

        waitForScript("document.activeElement.id === 'remove' && getComputedStyle(document.activeElement).opacity === '1'")
        tap()

        waitForScript("window.deleted")
        assertEquals("1", evaluate("window.clicks"))
        assertEquals("true", evaluate("window.lastClickTrusted"))
        assertEquals("\"keep this unsent\"", evaluate("document.getElementById('draft').value"))
        assertEquals("0", evaluate("window.sends"))
        assertEquals(1, loads.get())
    }

    @Test
    fun draggingCancellationAndTwoFingerScrollingNeverClick() {
        showFixture()
        setMouseMode(true)
        drag(dragDistance(), 0f)
        touchGesture(listOf(
            MotionEvent.ACTION_DOWN to listOf(point()),
            MotionEvent.ACTION_CANCEL to listOf(point()),
        ))
        val first = point()
        val second = first.copy(x = first.x + 80f)
        val distance = host.get().height * .12f
        touchGesture(listOf(
            MotionEvent.ACTION_DOWN to listOf(first),
            (MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to listOf(first, second),
            MotionEvent.ACTION_MOVE to listOf(first.copy(y = first.y - distance), second.copy(y = second.y - distance)),
            (MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)) to listOf(first.copy(y = first.y - distance), second.copy(y = second.y - distance)),
            MotionEvent.ACTION_UP to listOf(first.copy(y = first.y - distance)),
        ))

        waitForScript("window.wheels > 0 && scrollY > 0")
        assertEquals("0", evaluate("window.clicks"))
        assertEquals("false", evaluate("window.deleted"))
    }

    @Test
    fun disablingModeClearsHoverAndKeepsTheDocumentAndDraft() {
        showFixture()
        val identity = evaluate("window.identity")
        evaluate("document.getElementById('draft').value = 'unsent attachment draft'")
        setMouseMode(true)
        drag(dragDistance(), 0f)
        waitForScript("document.getElementById('attachment').matches(':hover')")
        setMouseMode(false)

        waitForScript("!document.getElementById('attachment').matches(':hover')")
        waitForScript("window.originalMedia === [...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText")
        setMouseMode(true)
        setMouseMode(false)
        assertEquals(identity, evaluate("window.identity"))
        assertEquals("\"unsent attachment draft\"", evaluate("document.getElementById('draft').value"))
        assertEquals(1, loads.get())
        assertEquals("0", evaluate("window.clicks"))
    }

    @Test
    fun lateHoverStylesAndOppositeTouchConditionsRestoreAcrossModeChanges() {
        showFixture()
        setMouseMode(true)
        evaluate("""
            const late = document.createElement('style');
            late.id = 'late-styles';
            late.textContent = '@layer compat { @media (min-width: 1px) and (any-hover: hover) { #attachment { --late-hover: active; } } } @media (hover: none) { #attachment { --touch-device: active; } }';
            document.head.appendChild(late);
        """.trimIndent())
        waitForScript("getComputedStyle(document.getElementById('attachment')).getPropertyValue('--late-hover').trim() === 'active'")
        assertEquals("\"\"", evaluate("getComputedStyle(document.getElementById('attachment')).getPropertyValue('--touch-device').trim()"))
        setMouseMode(false)
        waitForScript("getComputedStyle(document.getElementById('attachment')).getPropertyValue('--late-hover').trim() === ''")
        assertEquals("\"active\"", evaluate("getComputedStyle(document.getElementById('attachment')).getPropertyValue('--touch-device').trim()"))
        setMouseMode(true)
        waitForScript("getComputedStyle(document.getElementById('attachment')).getPropertyValue('--late-hover').trim() === 'active'")
        setMouseMode(false)
        waitForScript("document.getElementById('late-styles').sheet.cssRules[0].cssRules[0].conditionText.includes('any-hover: hover')")
        assertEquals("0", evaluate("window.clicks"))
        assertEquals("0", evaluate("window.sends"))
        assertEquals(1, loads.get())
    }

    @Test
    fun losingWindowFocusRestoresMediaAndCancelsAnUnfinishedTap() {
        showFixture()
        setMouseMode(true)
        waitForScript("!([...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText.includes('hover: hover'))")
        touchGesture(listOf(MotionEvent.ACTION_DOWN to listOf(point())))
        compose.runOnUiThread { host.get().onWindowFocusChanged(false) }
        waitForScript("window.originalMedia === [...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText")
        touchGesture(listOf(MotionEvent.ACTION_UP to listOf(point())))
        assertEquals("0", evaluate("window.clicks"))
        compose.runOnUiThread { host.get().onWindowFocusChanged(true) }
        waitForScript("!([...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText.includes('hover: hover'))")
        assertEquals("false", evaluate("window.deleted"))
        assertEquals(1, loads.get())
    }

    @Test
    fun replacingDocumentReconcilesEnabledMouseModeWithoutOldGestures() {
        showFixture()
        setMouseMode(true)
        touchGesture(listOf(MotionEvent.ACTION_DOWN to listOf(point())))
        compose.runOnUiThread {
            host.get().cancelPointer()
            host.get().webView.loadDataWithBaseURL("https://mouse-fixture.invalid/", FIXTURE, "text/html", "UTF-8", null)
        }
        compose.waitUntil(15_000) { loads.get() == 2 }
        waitForScript("window.ready === true && !([...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText.includes('hover: hover'))")
        touchGesture(listOf(MotionEvent.ACTION_UP to listOf(point())))
        assertEquals("0", evaluate("window.clicks"))
        assertEquals("false", evaluate("window.deleted"))
    }

    @Test
    fun interruptedPointerGestureDoesNotSwallowANewOrdinaryTouch() {
        showFixture()
        setMouseMode(true)
        touchGesture(listOf(MotionEvent.ACTION_DOWN to listOf(point())))
        compose.runOnUiThread {
            host.get().cancelPointer()
            host.get().mouseModeEnabled = false
        }
        val target = fixedButtonCenter()
        touchGesture(listOf(
            MotionEvent.ACTION_DOWN to listOf(target),
            MotionEvent.ACTION_UP to listOf(target),
        ))

        waitForScript("window.plainClicks === 1")
        assertEquals("1", evaluate("window.clicks"))
        assertEquals("false", evaluate("window.deleted"))
        assertEquals("\"touch\"", evaluate("window.lastPointerType"))
    }

    @Test
    fun hidingTheSurfaceClearsHoverAndCancelsAnUnfinishedTap() {
        showFixture()
        setMouseMode(true)
        drag(dragDistance(), 0f)
        waitForScript("document.getElementById('attachment').matches(':hover')")
        touchGesture(listOf(MotionEvent.ACTION_DOWN to listOf(point())))
        compose.runOnUiThread { host.get().visibility = View.INVISIBLE }

        waitForScript("!document.getElementById('attachment').matches(':hover')")
        waitForScript("window.originalMedia === [...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText")
        compose.runOnUiThread { host.get().visibility = View.VISIBLE }
        touchGesture(listOf(MotionEvent.ACTION_UP to listOf(point())))

        assertEquals("0", evaluate("window.clicks"))
        assertEquals("false", evaluate("window.deleted"))
        assertEquals(1, loads.get())
    }

    @Test
    fun disabledModeKeepsTouchAndEnabledModeAcceptsHardwareMouse() {
        showFixture()
        assertEquals(false, host.get().mouseModeEnabled)
        val target = fixedButtonCenter()
        touchGesture(listOf(
            MotionEvent.ACTION_DOWN to listOf(target),
            MotionEvent.ACTION_UP to listOf(target),
        ))
        waitForScript("window.plainClicks === 1")
        assertEquals("\"touch\"", evaluate("window.lastPointerType"))

        setMouseMode(true)
        val browser = host.get().webView as RecordingWebView
        browser.mouseEvents.clear()
        compose.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            mouseEvent(now, MotionEvent.ACTION_HOVER_ENTER, target).useEvent { host.get().dispatchGenericMotionEvent(it) }
            mouseEvent(now, MotionEvent.ACTION_HOVER_MOVE, target).useEvent { host.get().dispatchGenericMotionEvent(it) }
            mouseEvent(now, MotionEvent.ACTION_DOWN, target, MotionEvent.BUTTON_PRIMARY).useEvent { host.get().dispatchTouchEvent(it) }
            mouseEvent(now + 32, MotionEvent.ACTION_UP, target).useEvent { host.get().dispatchTouchEvent(it) }
        }
        assertMouseEvent(browser, MotionEvent.ACTION_HOVER_MOVE, 0, target)
        assertMouseEvent(browser, MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, target)
        assertMouseEvent(browser, MotionEvent.ACTION_UP, 0, target)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showFixture() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
        compose.setContent {
            DisposableEffect(Unit) { onDispose { host.get()?.webView?.destroy() } }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                val browser = RecordingWebView(context).apply {
                    settings.javaScriptEnabled = true
                    val bridge = object {
                        @JavascriptInterface fun getState(documentId: String): String = "null"
                        @JavascriptInterface fun setState(documentId: String, state: String) {}
                        @JavascriptInterface fun postMessage(documentId: String, message: String) {}
                        @JavascriptInterface fun ready(documentId: String) {}
                    }
                    addJavascriptInterface(bridge, "CodexExtensionBridge")
                    val source = context.assets.open("bridge.js").bufferedReader().use { it.readText() }
                    val config = JSONObject().put("documentId", "mouse-fixture").put("theme", "light")
                        .put("fontScale", 1).put("themeCss", "").put("mouseMode", false)
                    WebViewCompat.addDocumentStartJavaScript(this,
                        "window.__codexExtensionConfig = $config;\n$source", setOf("https://mouse-fixture.invalid"))
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            host.get()?.onDocumentReady()
                            loads.incrementAndGet()
                        }
                    }
                    loadDataWithBaseURL("https://mouse-fixture.invalid/", FIXTURE, "text/html", "UTF-8", null)
                }
                MouseModeWebViewContainer(context, browser).also(host::set)
            })
        }
        compose.waitUntil(15_000) { loads.get() == 1 && host.get()?.width?.let { it > 0 } == true }
        waitForScript("window.ready === true")
    }

    private fun setMouseMode(enabled: Boolean) = compose.runOnUiThread { host.get().mouseModeEnabled = enabled }

    private fun dragDistance(): Float = ViewConfiguration.get(host.get().context).scaledTouchSlop * 3f + 8f

    private fun point() = Point(host.get().width * .25f, host.get().height * .75f)

    private fun drag(dx: Float, dy: Float) {
        val start = point()
        val end = Point(start.x + dx, start.y + dy)
        touchGesture(listOf(
            MotionEvent.ACTION_DOWN to listOf(start),
            MotionEvent.ACTION_MOVE to listOf(end),
            MotionEvent.ACTION_UP to listOf(end),
        ))
    }

    private fun tap() = touchGesture(listOf(
        MotionEvent.ACTION_DOWN to listOf(point()),
        MotionEvent.ACTION_UP to listOf(point()),
    ))

    private fun touchGesture(events: List<Pair<Int, List<Point>>>) {
        compose.runOnUiThread {
            val downTime = SystemClock.uptimeMillis()
            events.forEachIndexed { index, (action, points) ->
                MotionEvent.obtain(
                    downTime, downTime + index * 32L, action, points.size,
                    Array(points.size) { pointer -> MotionEvent.PointerProperties().apply {
                        id = pointer
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    } },
                    Array(points.size) { pointer -> MotionEvent.PointerCoords().apply {
                        x = points[pointer].x
                        y = points[pointer].y
                        pressure = 1f
                        size = 1f
                    } },
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                ).useEvent { host.get().dispatchTouchEvent(it) }
            }
        }
    }

    private fun mouseEvent(time: Long, action: Int, point: Point, buttons: Int = 0): MotionEvent = MotionEvent.obtain(
        time, time, action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }),
        arrayOf(MotionEvent.PointerCoords().apply { x = point.x; y = point.y; pressure = if (buttons == 0) 0f else 1f }),
        0, buttons, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
    )

    private fun fixedButtonCenter(): Point {
        val rect = JSONObject(evaluate("(() => { const r = document.getElementById('plain').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2, scale: innerWidth }; })()"))
        val scale = host.get().webView.width / rect.getDouble("scale")
        return Point((rect.getDouble("x") * scale).toFloat(), (rect.getDouble("y") * scale).toFloat())
    }

    private fun assertMouseEvent(browser: RecordingWebView, action: Int, buttons: Int, point: Point) {
        assertTrue("Expected mouse action=$action buttons=$buttons at $point; recorded ${browser.mouseEvents}", browser.mouseEvents.any {
            it.action == action && it.source == InputDevice.SOURCE_MOUSE && it.toolType == MotionEvent.TOOL_TYPE_MOUSE &&
                it.buttons == buttons && abs(it.point.x - point.x) <= 1f && abs(it.point.y - point.y) <= 1f
        })
    }

    private fun waitForScript(script: String) {
        compose.waitUntil(10_000) { evaluate(script) == "true" }
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference<String>()
        compose.runOnUiThread { host.get().webView.evaluateJavascript(script) { result.set(it) } }
        compose.waitUntil(5_000) { result.get() != null }
        return result.get()
    }

    private inline fun MotionEvent.useEvent(block: (MotionEvent) -> Unit) = try { block(this) } finally { recycle() }

    private data class Point(val x: Float, val y: Float)

    private data class MouseEvent(val action: Int, val source: Int, val toolType: Int, val buttons: Int, val point: Point)

    private class RecordingWebView(context: android.content.Context) : WebView(context) {
        val mouseEvents = CopyOnWriteArrayList<MouseEvent>()
        override fun onTouchEvent(event: MotionEvent): Boolean {
            record(event)
            return super.onTouchEvent(event)
        }
        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            record(event)
            return super.onGenericMotionEvent(event)
        }
        override fun onHoverEvent(event: MotionEvent): Boolean {
            record(event)
            return super.onHoverEvent(event)
        }
        private fun record(event: MotionEvent) {
            if (event.isFromSource(InputDevice.SOURCE_MOUSE)) mouseEvents += MouseEvent(
                event.actionMasked, event.source, event.getToolType(0), event.buttonState, Point(event.x, event.y),
            )
        }
    }

    companion object {
        private val FIXTURE = """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style id="fixture-styles">
            body { margin: 0; min-height: 300vh; }
            #attachment { position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%); width: 70vw; height: 40vh; background: #ccc; }
            #remove { position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); width: 90px; height: 64px; opacity: 0; pointer-events: none; }
            @media (hover: hover) { #attachment:hover #remove { opacity: 1; pointer-events: auto; } }
            #remove:focus-visible { opacity: 1 !important; pointer-events: auto !important; }
            #plain { position: fixed; top: 12px; left: 12px; width: 90px; height: 64px; }
            #draft { position: fixed; left: 12px; bottom: 12px; }
            </style></head><body>
            <div id="attachment"><button id="remove">Remove</button></div>
            <button id="plain">Plain button</button><input id="draft">
            <script>
            window.identity = Math.random().toString();
            window.originalMedia = [...document.getElementById('fixture-styles').sheet.cssRules].find(rule => rule instanceof CSSMediaRule).conditionText;
            window.clicks = 0; window.plainClicks = 0; window.deleted = false; window.wheels = 0; window.sends = 0;
            document.addEventListener('keydown', e => { if (e.key === 'Enter') window.sends++; });
            // The attachment deliberately stops bubbling, as the official button does.
            document.addEventListener('pointerdown', e => window.lastPointerType = e.pointerType, true);
            document.addEventListener('click', e => { window.clicks++; window.lastClickTrusted = e.isTrusted; });
            document.addEventListener('wheel', () => window.wheels++, { passive: true });
            document.getElementById('plain').onclick = () => window.plainClicks++;
            document.getElementById('remove').onclick = () => { window.deleted = true; document.getElementById('attachment').remove(); };
            window.ready = true;
            </script></body></html>
        """.trimIndent()
    }
}
