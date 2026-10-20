package dev.codexremote.app.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.PreviewContent
import dev.codexremote.app.data.readPreview
import dev.codexremote.app.data.uploadAttachments
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AttachmentsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun binaryDownloadSurvivesHiddenChatAndPickerCancellation() {
        val visible = mutableStateOf(true)
        val bytes = ByteArray(5_877_760).also {
            java.util.Random(42).nextBytes(it)
            it[0] = 'M'.code.toByte()
            it[1] = 'Z'.code.toByte()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val destination = File.createTempFile("download-", ".exe", context.cacheDir)
        val registry = object : ActivityResultRegistry() {
            var launchedCode = 0
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launchedCode = requestCode
                assertEquals(Intent.ACTION_CREATE_DOCUMENT, contract.createIntent(context, input).action)
                visible.value = false // The system save picker backgrounds the chat Activity.
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        try {
            FixtureGateway(bytes).use { gateway ->
                compose.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        CodexRemoteTheme {
                            FilePreviewDialog(
                                FilePreview("binary.exe", "/binary.exe", PreviewContent.Unsupported("二进制文件")),
                                api = gateway.api, serviceId = "fixture", visible = visible.value,
                            ) {}
                        }
                    }
                }
                compose.onNodeWithText("下载").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("binary.exe").assertDoesNotExist()
                compose.runOnIdle {
                    registry.dispatchResult(registry.launchedCode, Activity.RESULT_CANCELED, null)
                    visible.value = true
                }
                compose.waitForIdle()
                assertTrue(gateway.requests.isEmpty())
                compose.onNodeWithText("下载").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("binary.exe").assertDoesNotExist()
                compose.runOnIdle {
                    registry.dispatchResult(registry.launchedCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination)))
                }
                // Deliver while still hidden, so a resumed recomposition cannot repair a lost callback.
                compose.waitUntil(15_000) { destination.length() == bytes.size.toLong() }
                compose.runOnIdle { visible.value = true }
                compose.waitUntil(5_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText("已保存到所选位置")).fetchSemanticsNodes().isNotEmpty()
                }
                assertArrayEquals(bytes, destination.readBytes())
                assertEquals(1, gateway.requests.count { it.first.contains("/fs/download?") })
            }
        } finally { destination.delete() }
    }

    @Test fun windowsAttachmentUploadPreservesBytesAndUsesTheRemoteHome() = runBlocking {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "手机图片.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        })!!
        val bytes = png()
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        try {
            FixtureGateway(bytes).use { gateway ->
                val selection = uploadAttachments(gateway.api, "windows", "C:\\Users\\用户", resolver,
                    listOf(uri), JSONObject().put("imagesOnly", true)) {}
                val file = selection.getJSONObject(0)
                assertTrue(file.getString("path").startsWith("C:/Users/用户/.codex-remote-uploads/"))
                assertTrue(file.getString("path").endsWith("/手机图片.png"))
                assertEquals(file.getString("path"), file.getString("fsPath"))
                val upload = gateway.requests.single { it.first.contains("/fs/upload?") }
                assertTrue(URLDecoder.decode(upload.first, "UTF-8").contains(file.getString("path")))
                assertTrue(upload.second.toString(Charsets.ISO_8859_1).contains(bytes.toString(Charsets.ISO_8859_1)))
            }
        } finally { resolver.delete(uri, null, null) }
    }

    @Test fun hiddenPageDefersPickerAndBackgroundingDoesNotCancelAnOpenPicker() {
        val visible = mutableStateOf(false)
        val completed = AtomicReference<JSONArray>()
        val registry = object : ActivityResultRegistry() {
            var launchedCode: Int? = null
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launchedCode = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                val picker = rememberOfficialFilePicker(GatewayApi(), "fixture", "/tmp", "/tmp", visible.value) {}
                val scope = rememberCoroutineScope()
                TextButton(onClick = { scope.launch { completed.set(picker(JSONObject().put("imagesOnly", true))) } }) { Text("请求文件") }
            }
        }
        compose.onNodeWithText("请求文件").performClick()
        compose.runOnIdle { assertNull(registry.launchedCode); visible.value = true }
        compose.waitUntil(5000) { registry.launchedCode != null }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(completed.get())
            registry.dispatchResult(registry.launchedCode!!, Activity.RESULT_CANCELED, null)
        }
        compose.waitUntil(5000) { completed.get() != null }
        assertEquals(0, completed.get().length())
    }

    @Test fun binaryImageIsDecodedAndRenderedEvenWithATextFilename() = runBlocking {
        val bytes = png(512, noise = true)
        assertTrue("Fixture must exceed the old text preview limit", bytes.size > 256 * 1024)
        FixtureGateway(bytes).use { gateway ->
            val content = gateway.api.preview("fixture", "/looks-like-text.txt")
            assertTrue(content is PreviewContent.Image)
            assertEquals(Color.RED, (content as PreviewContent.Image).bitmap.getPixel(0, 0))
            assertTrue(gateway.requests.single().first.contains("/fs/download?"))
            compose.setContent { CodexRemoteTheme { FilePreviewDialog(FilePreview("photo.png", "/photo.png", content)) {} } }
            compose.onNodeWithContentDescription("photo.png").assertExists()
            compose.onNodeWithText("双指缩放，拖动查看").assertExists()
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot().let { screenshot ->
                File(instrumentation.targetContext.getExternalFilesDir(null), "attachment-preview.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
        }
        assertTrue(readPreview(byteArrayOf(0, 1, 2, 3).inputStream()) is PreviewContent.Unsupported)
    }

    @Test fun pickerCancelDoesNotUploadAndSelectionPreservesBytesAndReturnsRemotePaths() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "phone-photo.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        })!!
        val bytes = png()
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        try {
            FixtureGateway(bytes).use { gateway ->
                val completed = AtomicReference<JSONArray>()
                val registry = object : ActivityResultRegistry() {
                    var launchedCode = 0
                    var intent: Intent? = null
                    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                        launchedCode = requestCode
                        intent = contract.createIntent(InstrumentationRegistry.getInstrumentation().targetContext, input)
                    }
                }
                val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
                compose.setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        val picker = rememberOfficialFilePicker(gateway.api, "fixture", "/home/test", "/home/test", true) {}
                        val scope = rememberCoroutineScope()
                        TextButton(onClick = { scope.launch { completed.set(picker(JSONObject().put("imagesOnly", true))) } }) { Text("选择图片") }
                    }
                }
                compose.onNodeWithText("选择图片").performClick()
                compose.runOnIdle {
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, registry.intent?.action)
                    assertEquals("image/*", registry.intent?.type)
                    registry.dispatchResult(registry.launchedCode, Activity.RESULT_CANCELED, null)
                }
                compose.waitUntil(5_000) { completed.get() != null }
                assertEquals(0, completed.get().length())
                assertTrue(gateway.requests.isEmpty())
                completed.set(null)
                compose.onNodeWithText("选择图片").performClick()
                compose.runOnIdle { registry.dispatchResult(registry.launchedCode, Activity.RESULT_OK, Intent().setData(uri)) }
                compose.waitUntil(10_000) { completed.get() != null }
                val file = completed.get().getJSONObject(0)
                assertEquals("phone-photo.png", file.getString("label"))
                assertTrue(file.getString("fsPath").startsWith("/home/test/.codex-remote-uploads/"))
                assertEquals(file.getString("path"), file.getString("fsPath"))
                val upload = gateway.requests.single { it.first.contains("/fs/upload?") }.second
                assertTrue("Multipart upload must contain the original PNG bytes", upload.toString(Charsets.ISO_8859_1).contains(bytes.toString(Charsets.ISO_8859_1)))
            }
        } finally { resolver.delete(uri, null, null) }
    }

    private fun png(size: Int = 32, noise: Boolean = false): ByteArray = ByteArrayOutputStream().also { output ->
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        if (noise) {
            val random = java.util.Random(42)
            val pixels = IntArray(size * size) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap.setPixel(0, 0, Color.RED)
        }
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
    }.toByteArray()

    private class FixtureGateway(private val image: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0)
        val api = GatewayApi().apply { configure(GatewayConfig("http://127.0.0.1:${server.localPort}", "fixture", allowHttp = true)) }
        val requests = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use {
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val result = StringBuilder()
                            while (true) { val next = input.read(); if (next < 0 || next == 10) break; if (next != 13) result.append(next.toChar()) }
                            return result.toString()
                        }
                        val request = line()
                        var length = 0
                        var chunked = false
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                            if (header.startsWith("Transfer-Encoding:", true)) chunked = header.substringAfter(':').trim().equals("chunked", true)
                        }
                        fun readBytes(size: Int): ByteArray = ByteArray(size).apply {
                            var offset = 0
                            while (offset < size) { val count = input.read(this, offset, size - offset); check(count >= 0); offset += count }
                        }
                        val body = if (chunked) ByteArrayOutputStream().apply {
                            while (true) {
                                val size = line().substringBefore(';').toInt(16)
                                if (size == 0) { while (line().isNotEmpty()) Unit; break }
                                write(readBytes(size))
                                check(line().isEmpty())
                            }
                        }.toByteArray() else readBytes(length)
                        requests += request to body
                        val response = if (request.contains("/fs/download?")) image else
                            (if (request.contains("/fs/list?")) "{\"data\":[]}" else "{\"data\":{}}").toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                            write(response)
                            flush()
                        }
                    }
                }
            }
        }
        override fun close() { api.clear(); server.close() }
    }
}
