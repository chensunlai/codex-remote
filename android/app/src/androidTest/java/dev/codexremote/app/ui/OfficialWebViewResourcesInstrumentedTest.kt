package dev.codexremote.app.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficialWebViewResourcesInstrumentedTest {
    private val host = "resources.codex-remote.invalid"

    @Test
    fun browserFaviconIsEmptyWithoutFetchingOrReportingFailure() {
        repeat(2) {
            val response = officialWebViewResource(Uri.parse("https://$host/favicon.ico"), host,
                load = { _, _ -> error("Favicon must not fetch extension resources") },
                onFailure = { throw AssertionError("Favicon must not report a frontend failure", it) })!!
            assertEquals(204, response.statusCode)
            assertEquals(0, response.data.readBytes().size)
        }
    }

    @Test
    fun externalFaviconIsNotIntercepted() {
        assertNull(officialWebViewResource(Uri.parse("https://other.invalid/favicon.ico"), host,
            load = { _, _ -> error("External resource fetched") },
            onFailure = { throw AssertionError("External resource reported", it) }))
    }

    @Test
    fun requiredModuleRetainsVersionPathMimeAndBytes() {
        val bytes = "export default 1".toByteArray()
        val response = officialWebViewResource(Uri.parse("https://$host/extension/26.1/webview/main.mjs"), host,
            load = { version, path ->
                assertEquals("26.1", version)
                assertEquals("webview/main.mjs", path)
                bytes
            }, onFailure = { throw AssertionError(it) })!!
        assertEquals(200, response.statusCode)
        assertEquals("text/javascript", response.mimeType)
        assertArrayEquals(bytes, response.data.readBytes())
    }

    @Test
    fun requiredResourceFailuresAndInvalidPathsStillReportErrors() {
        val failures = mutableListOf<Throwable>()
        val missing = IllegalStateException("Missing required stylesheet")
        val response = officialWebViewResource(Uri.parse("https://$host/extension/26.1/webview/main.css"), host,
            load = { _, _ -> throw missing }, onFailure = failures::add)!!
        assertEquals(502, response.statusCode)
        assertEquals(listOf(missing), failures)

        val invalid = officialWebViewResource(Uri.parse("https://$host/invalid.js"), host,
            load = { _, _ -> error("Invalid path fetched") }, onFailure = failures::add)!!
        assertEquals(502, invalid.statusCode)
        assertEquals("资源路径无效", failures.last().message)
    }
}
