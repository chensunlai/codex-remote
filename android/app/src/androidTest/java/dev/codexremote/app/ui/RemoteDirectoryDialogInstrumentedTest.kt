package dev.codexremote.app.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.inspector.WindowInspector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.GatewayConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RemoteDirectoryDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigatingRemovesStaleRowsAndCannotSelectUntilTheNewDirectoryLoads() {
        val release = CountDownLatch(1)
        DirectoryGateway { path, _ ->
            if (path == "/home/csl") listing(path, "projects", "notes")
            else {
                release.await(10, TimeUnit.SECONDS)
                listing(path)
            }
        }.use { gateway ->
            val selected = AtomicReference<String>()
            compose.setContent {
                CodexRemoteTheme {
                    RemoteDirectoryDialog(gateway.api, "fixture", "/home/csl", { selected.set(it) }, {})
                }
            }
            compose.waitUntil(5_000) { gateway.requests.contains("/home/csl") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("projects").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("projects").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { gateway.requests.contains("/home/csl/projects") }
            compose.onNodeWithText("notes").assertDoesNotExist()
            compose.onNodeWithText("使用此目录").assertIsNotEnabled()
            compose.onNodeWithText("正在读取目录…").assertExists()
            release.countDown()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("没有子文件夹").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("使用此目录").assertIsEnabled().performClick()
            assertEquals("/home/csl/projects", selected.get())
        }
    }

    @Test fun typedPathsUseImeGoAndFailedPathsCanRetryWithoutChangingThePath() {
        DirectoryGateway { path, attempt ->
            if (path == "/empty" && attempt == 1) Response(403, "{\"error\":{\"message\":\"暂时无法读取\"}}")
            else listing(path, *if (path == "/home/csl") arrayOf("projects") else emptyArray())
        }.use { gateway ->
            val selected = AtomicReference<String>()
            compose.setContent {
                CodexRemoteTheme {
                    RemoteDirectoryDialog(gateway.api, "fixture", "/home/csl", { selected.set(it) }, {})
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("projects").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("绝对路径").performTextReplacement("/empty")
            compose.onNodeWithText("使用此目录").assertIsNotEnabled()
            compose.onNodeWithContentDescription("绝对路径").performImeAction()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("无法读取目录").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("使用此目录").assertIsNotEnabled()
            compose.onNodeWithText("projects").assertDoesNotExist()
            compose.onNodeWithText("重试").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("没有子文件夹").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, gateway.requests.count { it == "/empty" })
            compose.onNodeWithContentDescription("返回父目录").performClick()
            compose.waitUntil(5_000) { gateway.requests.contains("/") }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("正在读取目录…").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("返回父目录").assertIsNotEnabled()
            compose.onNodeWithText("使用此目录").performClick()
            assertEquals("/", selected.get())
        }
    }

    @Test fun allThreeThemesUseCompactFolderRowsAndSaveScreenshots() {
        DirectoryGateway { path, _ -> listing(path, ".ai2thor", ".android", ".bun", ".cache", ".claude", ".codex", "Downloads", "Project") }.use { gateway ->
            val theme = mutableStateOf(AppTheme.LIGHT)
            compose.setContent {
                CodexRemoteTheme(theme = theme.value) {
                    RemoteDirectoryDialog(gateway.api, "fixture", "/home/csl", {}, {})
                }
            }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(".ai2thor").fetchSemanticsNodes().isNotEmpty() }
            AppTheme.entries.forEach { current ->
                compose.runOnIdle { theme.value = current }
                compose.waitForIdle()
                compose.onNodeWithText(".ai2thor").assertHasClickAction().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithText("使用此目录").assertIsEnabled()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                    File(instrumentation.targetContext.getExternalFilesDir(null), "directory-picker-${current.id}.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
            }
        }
    }

    @Test
    fun landscapeKeyboardKeepsTheFooterVisibleAndUsable() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalConfiguration = instrumentation.targetContext.resources.configuration.orientation
        var originalRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fun currentActivity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
        instrumentation.runOnMainSync {
            currentActivity().let {
                originalRequestedOrientation = it.requestedOrientation
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        try {
            compose.waitUntil(10_000) {
                instrumentation.targetContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            DirectoryGateway { path, _ -> listing(path, "projects", "notes", "Downloads", "Documents") }.use { gateway ->
                val dismissed = AtomicBoolean()
                compose.setContent {
                    CodexRemoteTheme {
                        RemoteDirectoryDialog(gateway.api, "fixture", "/home/csl", {}, { dismissed.set(true) })
                    }
                }
                compose.waitUntil(5_000) { compose.onAllNodesWithText("projects").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("绝对路径").performClick()
                compose.waitUntil(10_000) {
                    var keyboardVisible = false
                    instrumentation.runOnMainSync {
                        keyboardVisible = WindowInspector.getGlobalWindowViews().any {
                            ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                        }
                    }
                    keyboardVisible
                }
                var previousBounds: Rect? = null
                var stableSince = SystemClock.uptimeMillis()
                compose.waitUntil(5_000) {
                    val button = compose.onNodeWithText("取消")
                    val bounds = button.fetchSemanticsNode().boundsInWindow
                    val displayed = runCatching { button.assertIsDisplayed() }.isSuccess
                    val now = SystemClock.uptimeMillis()
                    if (bounds != previousBounds || !displayed) {
                        previousBounds = bounds
                        stableSince = now
                    }
                    displayed && now - stableSince >= 350
                }
                compose.onNodeWithText("取消").assertIsDisplayed()
                compose.onNodeWithText("使用此目录").assertIsDisplayed().assertIsEnabled()
                instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                    File(instrumentation.targetContext.getExternalFilesDir(null), "directory-picker-landscape-ime.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                compose.onNodeWithText("取消").performClick()
                compose.waitUntil(5_000) { dismissed.get() }
            }
        } finally {
            instrumentation.runOnMainSync { currentActivity().requestedOrientation = originalRequestedOrientation }
            compose.waitUntil(10_000) {
                instrumentation.targetContext.resources.configuration.orientation == originalConfiguration
            }
        }
    }

    private data class Response(val status: Int, val body: String)

    private fun listing(path: String, vararg folders: String) = Response(200, JSONObject().put("data", JSONArray().apply {
        folders.forEach { name -> put(JSONObject().put("name", name).put("path", "${path.trimEnd('/')}/$name").put("type", "directory")) }
        put(JSONObject().put("name", "file.txt").put("path", "${path.trimEnd('/')}/file.txt").put("type", "file"))
    }).toString())

    private class DirectoryGateway(private val respond: (String, Int) -> Response) : AutoCloseable {
        private val server = ServerSocket(0)
        val api = GatewayApi().apply { configure(GatewayConfig("http://127.0.0.1:${server.localPort}", "fixture", allowHttp = true)) }
        val requests = CopyOnWriteArrayList<String>()
        private val attempts = ConcurrentHashMap<String, Int>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        socket.use {
                            val reader = socket.getInputStream().bufferedReader()
                            val target = reader.readLine().substringAfter(' ').substringBefore(' ')
                            while (!reader.readLine().isNullOrEmpty()) { /* Consume request headers. */ }
                            val query = URI(target).rawQuery.orEmpty().split('&').associate {
                                val parts = it.split('=', limit = 2)
                                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                            }
                            val path = query.getValue("path")
                            requests += path
                            val response = respond(path, attempts.merge(path, 1, Int::plus)!!)
                            val bytes = response.body.toByteArray()
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 ${response.status} Fixture\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }
            }
        }

        override fun close() { api.clear(); server.close() }
    }
}
