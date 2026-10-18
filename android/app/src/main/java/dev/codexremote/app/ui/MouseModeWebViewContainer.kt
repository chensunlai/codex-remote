package dev.codexremote.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.withSave
import kotlin.math.hypot

/** Native trackpad input. The page, its elements and its input handlers remain untouched. */
internal class MouseModeWebViewContainer(context: Context, val webView: WebView) : FrameLayout(context) {
    private val configuration = ViewConfiguration.get(context)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private val cursorScale = resources.displayMetrics.density
    private val cursor = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 22f); lineTo(6f, 16f); lineTo(11f, 26f)
        lineTo(15f, 24f); lineTo(10f, 14f); lineTo(20f, 14f); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeJoin = Paint.Join.ROUND
    }
    private var cursorX = 0f
    private var cursorY = 0f
    private var positioned = false
    private var hovering = false
    private var hardwarePointer = false
    private var consumingTouch = false
    private var gestureActive = false
    private var tapCandidate = false
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var touchDownTime = 0L

    var mouseModeEnabled = false
        set(value) {
            if (field == value) return
            field = value
            if (value) { hardwarePointer = false; updateHover() } else cancelPointer()
            invalidate()
        }

    init { addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    private fun canInteract() = mouseModeEnabled && isShown && hasWindowFocus() && !accessibility.isTouchExplorationEnabled
    private fun canPoint() = canInteract() && positioned && !hardwarePointer

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!positioned && w > 0 && h > 0) { cursorX = w / 2f; cursorY = h / 2f; positioned = true }
        clampCursor()
        updateHover()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) updateHover() else cancelPointer()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) updateHover() else cancelPointer()
    }

    override fun onDetachedFromWindow() { cancelPointer(); super.onDetachedFromWindow() }

    /** Also used before replacing a document; no input from an old gesture reaches the new page. */
    fun cancelPointer() {
        gestureActive = false
        tapCandidate = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (hovering) {
            // Chromium ignores HOVER_EXIT on some versions; an outside move clears CSS :hover too.
            mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x = -1f, y = -1f)
            mouseEvent(MotionEvent.ACTION_HOVER_EXIT, x = -1f, y = -1f)
            hovering = false
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (canPoint()) {
            canvas.withSave {
                translate(cursorX, cursorY)
                scale(cursorScale, cursorScale)
                drawPath(cursor, fill)
                drawPath(cursor, outline)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            useHardwarePointer()
            return super.dispatchTouchEvent(event)
        }
        val finger = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        // A lost window can swallow a gesture's tail. A fresh DOWN always starts a new sequence.
        if (finger && event.actionMasked == MotionEvent.ACTION_DOWN) consumingTouch = false
        if (finger && event.actionMasked == MotionEvent.ACTION_DOWN && mouseModeEnabled &&
            isShown && hasWindowFocus() && !accessibility.isTouchExplorationEnabled) {
            hardwarePointer = false
            consumingTouch = true
            gestureActive = true
            tapCandidate = true
            touchDownTime = event.eventTime
            startX = event.x; startY = event.y
            lastX = event.x; lastY = event.y
            parent?.requestDisallowInterceptTouchEvent(true)
            updateHover()
        }
        if (!finger || !consumingTouch) return super.dispatchTouchEvent(event)
        if (gestureActive && canPoint()) when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                tapCandidate = false
                rememberCentroid(event, if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1)
            }
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_UP -> {
                move(event)
                if (tapCandidate && event.eventTime - touchDownTime <= ViewConfiguration.getLongPressTimeout()) clickAtCursor()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            consumingTouch = false
            gestureActive = false
            tapCandidate = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) useHardwarePointer()
        return super.dispatchGenericMotionEvent(event)
    }

    private fun useHardwarePointer() {
        cancelPointer()
        hardwarePointer = true
        invalidate()
    }

    private fun rememberCentroid(event: MotionEvent, skip: Int = -1) {
        var x = 0f; var y = 0f; var count = 0
        for (i in 0 until event.pointerCount) if (i != skip) { x += event.getX(i); y += event.getY(i); count++ }
        if (count > 0) { lastX = x / count; lastY = y / count }
    }

    private fun move(event: MotionEvent) {
        val previousX = lastX; val previousY = lastY
        rememberCentroid(event)
        val dx = lastX - previousX; val dy = lastY - previousY
        if (event.pointerCount > 1) {
            tapCandidate = false
            if (dx != 0f || dy != 0f) mouseEvent(MotionEvent.ACTION_SCROLL,
                horizontal = dx / configuration.scaledHorizontalScrollFactor,
                vertical = dy / configuration.scaledVerticalScrollFactor)
        } else {
            if (hypot(lastX - startX, lastY - startY) > configuration.scaledTouchSlop) tapCandidate = false
            cursorX += dx; cursorY += dy
            clampCursor()
            updateHover()
        }
    }

    private fun clampCursor() {
        cursorX = cursorX.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cursorY = cursorY.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
    }

    private fun updateHover() {
        if (!canPoint()) return
        if (!hovering) { mouseEvent(MotionEvent.ACTION_HOVER_ENTER); hovering = true }
        mouseEvent(MotionEvent.ACTION_HOVER_MOVE)
        invalidate()
    }

    private fun mouseEvent(action: Int, x: Float = cursorX, y: Float = cursorY, horizontal: Float = 0f, vertical: Float = 0f) {
        val now = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal); setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        }
        val event = MotionEvent.obtain(now, now, action, 1, arrayOf(properties), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        try { webView.dispatchGenericMotionEvent(event) } finally { event.recycle() }
    }

    private fun clickAtCursor() {
        // Android's public MotionEvent constructors cannot set actionButton. Chromium ignores mouse
        // DOWN/UP without BUTTON_PRESS/RELEASE, so activate with a native touch at the pointer instead.
        // This remains trusted browser input; no element lookup or JavaScript click is involved.
        webView.requestFocus()
        val down = SystemClock.uptimeMillis()
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val coords = MotionEvent.PointerCoords().apply {
                x = cursorX; y = cursorY; pressure = if (action == MotionEvent.ACTION_DOWN) 1f else 0f; size = 1f
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { webView.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        updateHover()
    }

    /** Focus navigation also works when the page gates hover UI on a physical mouse device. */
    fun navigateFocus(forward: Boolean) {
        if (!canInteract()) return
        webView.requestFocus()
        val modifiers = if (forward) 0 else KeyEvent.META_SHIFT_ON
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            webView.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_TAB, 0, modifiers,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
    }
}
