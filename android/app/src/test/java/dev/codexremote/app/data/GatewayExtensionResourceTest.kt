package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

class GatewayExtensionResourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packageId = "a".repeat(64)

    @Test fun secondWindowAndDiskReopenReuseTheSameVerifiedResourceWithoutMoreTraffic() {
        val bytes = "official unchanged JS 图片🙂".repeat(20_000).toByteArray()
        val compressed = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }.toByteArray()
        serveResource(compressed, digest(bytes), packageId, gzip = true) { api, _ ->
            val directory = temporary.newFolder()
            val cache = ExtensionResourceCache(directory)
            fun load(source: ExtensionResourceCache): ByteArray = source.load("owner-service", packageId, "1.0", "webview/index.js") {
                api.extensionResource("service", "1.0", "webview/index.js", packageId)
            }
            val first = System.nanoTime()
            assertArrayEquals(bytes, load(cache))
            val second = System.nanoTime()
            assertArrayEquals(bytes, load(cache))
            val reopen = System.nanoTime()
            assertArrayEquals(bytes, load(ExtensionResourceCache(directory)))
            val end = System.nanoTime()
            assertEquals(compressed.size.toLong(), api.resourceTrafficBytes())
            assertEquals(1L, cache.diagnostics().misses)
            assertEquals(1L, cache.diagnostics().hits)
            println("resource-reuse decodedBytes=${bytes.size} wireBytes=${api.resourceTrafficBytes()} firstMs=${(second-first)/1_000_000.0} secondWindowMs=${(reopen-second)/1_000_000.0} diskReopenMs=${(end-reopen)/1_000_000.0}")
        }
    }

    @Test fun checksExactDecodedBytesWhenGatewayCompressesResources() {
        val bytes = "unmodified official javascript 图片".repeat(100).toByteArray()
        val compressed = ByteArrayOutputStream().also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }.toByteArray()
        serveResource(compressed, digest(bytes), packageId, gzip = true) { api, request ->
            assertArrayEquals(bytes, api.extensionResource("service", "1.0", "webview/index.js", packageId))
            assertEquals(compressed.size.toLong(), api.resourceTrafficBytes())
            assertTrue(request.get().contains("packageSha256=$packageId"))
            assertTrue(request.get().contains("Authorization: Bearer owner-token", ignoreCase = true))
        }
    }

    @Test fun claudeResourcesKeepTheirToolRouteWhileCodexUsesTheLegacyRoute() {
        val bytes = "official Claude resource".toByteArray()
        serveResource(bytes, digest(bytes), packageId) { api, request ->
            assertArrayEquals(bytes, api.extensionResource("service", "1.0", "webview/index.js", packageId, "claude"))
            assertTrue(request.get().contains("toolId=claude"))
        }
        serveResource(bytes, digest(bytes), packageId) { api, request ->
            assertArrayEquals(bytes, api.extensionResource("service", "1.0", "webview/index.js", packageId))
            assertTrue(!request.get().contains("toolId="))
        }
    }

    @Test fun rejectsCorruptResourceBytes() {
        serveResource("changed".toByteArray(), digest("original".toByteArray()), packageId) { api, _ ->
            assertTrue(runCatching { api.extensionResource("service", "1.0", "index.js", packageId) }.isFailure)
        }
    }

    @Test fun rejectsSuccessfulHtmlWithoutAResourceChecksum() {
        serveResource("<html>sign in</html>".toByteArray(), null, null) { api, _ ->
            assertTrue(runCatching { api.extensionResource("service", "1.0", "index.js") }.isFailure)
        }
    }

    @Test fun rejectsAnUnexpectedPackageEvenIfTheResourceChecksumMatches() {
        val bytes = "resource".toByteArray()
        serveResource(bytes, digest(bytes), "b".repeat(64)) { api, _ ->
            assertTrue(runCatching { api.extensionResource("service", "1.0", "index.js", packageId) }.isFailure)
        }
    }

    @Test fun oldGatewayWithoutPackageIdentityCanStillLoadVerifiedResourcesWithoutDurableCaching() {
        val bytes = "resource".toByteArray()
        serveResource(bytes, digest(bytes), null) { api, _ ->
            assertArrayEquals(bytes, api.extensionResource("service", "1.0", "index.js"))
        }
    }

    private fun serveResource(
        bytes: ByteArray, sha: String?, packageSha: String?, gzip: Boolean = false,
        check: (GatewayApi, AtomicReference<String>) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val request = AtomicReference("")
            val worker = thread(isDaemon = true, name = "extension-resource-test") {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        lines += line
                    }
                    request.set(lines.joinToString("\n"))
                    val headers = buildString {
                        append("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
                        sha?.let { append("X-Resource-Sha256: $it\r\n") }
                        packageSha?.let { append("X-Extension-Package-Sha256: $it\r\n") }
                        if (gzip) append("Content-Encoding: gzip\r\n")
                        append("\r\n")
                    }
                    socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes); flush() }
                }
            }
            try {
                val api = GatewayApi().apply { configure(GatewayConfig("http://127.0.0.1:${server.localPort}", "owner-token", allowHttp = true)) }
                check(api, request)
            } finally { worker.join(2000) }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
