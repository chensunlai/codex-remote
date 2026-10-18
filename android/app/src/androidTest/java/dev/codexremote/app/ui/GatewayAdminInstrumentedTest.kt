package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import dev.codexremote.app.data.parseProductRelease
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.GatewayConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class GatewayAdminInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ordinaryAndLegacyAccountsHaveNoAdministrativeControlsOrRequests() {
        FixtureGateway().use { gateway ->
            val state = mutableStateOf(AppState(gatewayConfig = gateway.config, gatewayRole = "user", gatewayAdminSupported = true))
            compose.setContent { CodexRemoteTheme { GatewayAdministration(state.value) {} } }
            compose.onNodeWithText("Gateway 管理").assertDoesNotExist()
            compose.runOnIdle { state.value = state.value.copy(gatewayRole = "admin", gatewayAdminSupported = false) }
            compose.onNodeWithText("Gateway 管理").assertDoesNotExist()
            assertEquals(0, gateway.requests.size)
        }
    }

    @Test fun administratorCanSaveLimitsAndCreateTokenWithoutExposingExistingSecrets() {
        FixtureGateway().use { gateway ->
            var saved = 0
            compose.setContent { CodexRemoteTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                GatewayAdministration(gateway.state) { saved += 1 }
            } } }
            loaded()
            compose.onNodeWithText("当前管理员").assertExists()
            compose.onNodeWithText("撤销").assertIsNotEnabled()
            compose.onNodeWithText("参数").performClick()
            compose.onNodeWithText("上传上限（MiB）").performTextReplacement("20")
            compose.onNodeWithText("下载 / 预览上限（MiB）").performTextReplacement("30")
            compose.onNodeWithText("256", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("保存参数").performScrollTo().performClick()
            compose.waitUntil(5_000) { saved == 1 }
            val settings = gateway.requests.single { it.method == "PUT" }
            assertEquals(20L * 1024 * 1024, JSONObject(settings.body).getLong("maxUploadBytes"))
            assertEquals(30L * 1024 * 1024, JSONObject(settings.body).getLong("maxDownloadBytes"))
            assertEquals(256L * 1024 * 1024, JSONObject(settings.body).getLong("maxMessageBytes"))
            compose.onNodeWithText("访问令牌").performScrollTo().performClick()
            compose.onNodeWithText("创建令牌").performScrollTo().performClick()
            compose.onNodeWithText("名称").performTextReplacement("手机专用")
            assertFalse(gateway.requests.any { it.method == "POST" })
            compose.onNodeWithText("创建", substring = false).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("令牌已创建").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("new-test-secret").assertExists()
            assertEquals("user", JSONObject(gateway.requests.single { it.method == "POST" }.body).getString("role"))
            assertTrue(gateway.requests.all { it.authorization == "Bearer fixture-admin" })
        }
    }

    @Test fun gatewayMaintenanceRequiresConfirmationAndDisplaysChecks() {
        FixtureGateway().use { gateway ->
            compose.setContent { CodexRemoteTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                GatewayAdministration(gateway.state) {}
            } } }
            loaded()
            compose.onNodeWithText("检查更新", substring = false).performScrollTo().performClick()
            compose.waitUntil(5_000) { gateway.requests.any { it.path.endsWith("runtime?checkUpdates=true") } }
            compose.waitForIdle()
            compose.onNodeWithText("更新", substring = false).performScrollTo().assertIsEnabled().performClick()
            assertFalse(gateway.requests.any { it.method == "POST" })
            compose.onNodeWithText("取消", substring = false).performClick()
            assertFalse(gateway.requests.any { it.method == "POST" })
            compose.onNodeWithText("重启", substring = false).performScrollTo().performClick()
            compose.onNodeWithText("确认重启").performClick()
            compose.waitUntil(5_000) { gateway.requests.any { it.path.endsWith("/restart") } }
            assertTrue(JSONObject(gateway.requests.single { it.method == "POST" }.body).getBoolean("confirmed"))
        }
    }

    @Test fun changingAccountRemovesAdminStateAndCancelsPendingRequests() {
        FixtureGateway(holdTokens = true).use { gateway ->
            val state = mutableStateOf(gateway.state)
            compose.setContent { CodexRemoteTheme { GatewayAdministration(state.value) {} } }
            compose.waitUntil(5_000) { gateway.requests.any { it.path.endsWith("/tokens") } }
            compose.runOnIdle { state.value = AppState(gatewayConfig = GatewayConfig(gateway.config.baseUrl, "ordinary"), gatewayRole = "user", gatewayAdminSupported = true) }
            compose.onNodeWithText("Gateway 管理").assertDoesNotExist()
            compose.waitUntil(5_000) { gateway.cancelled }
            assertTrue(gateway.requests.all { it.authorization == "Bearer fixture-admin" })
        }
    }

    @Test fun rejectsDraftPrereleaseAndForeignDownloadSources() {
        val valid = """{"tag_name":"v2.1.0","html_url":"https://github.com/chensunlai/codex-remote/releases/tag/v2.1.0","assets":[{"name":"codex-remote-android.apk","browser_download_url":"https://github.com/chensunlai/codex-remote/releases/download/v2.1.0/codex-remote-android.apk"}]}"""
        assertNotNull(parseProductRelease(JSONObject(valid)).apkUrl)
        assertTrue(runCatching { parseProductRelease(JSONObject(valid).put("prerelease", true)) }.isFailure)
        assertTrue(runCatching { parseProductRelease(JSONObject(valid).put("draft", true)) }.isFailure)
        assertTrue(runCatching { parseProductRelease(JSONObject(valid).put("html_url", "https://evil.invalid/release")) }.isFailure)
        val foreign = JSONObject(valid)
        foreign.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.invalid/app.apk")
        assertNull(parseProductRelease(foreign).apkUrl)
    }

    private fun loaded() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("当前管理员").fetchSemanticsNodes().isNotEmpty() }
    }

    private data class Captured(val method: String, val path: String, val authorization: String?, val body: String)
    private class FixtureGateway(private val holdTokens: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        val requests = CopyOnWriteArrayList<Captured>()
        @Volatile var cancelled = false
        val config = GatewayConfig("http://127.0.0.1:${server.localPort}", "fixture-admin")
        val state = AppState(gatewayConfig = config, gatewayRole = "admin", gatewayTokenId = "current", gatewayAdminSupported = true)
        init { thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets += socket
                thread(isDaemon = true) { try { socket.use {
                    val input = it.getInputStream()
                    val line = readLine(input).split(' ')
                    val headers = buildMap { while (true) {
                        val header = readLine(input)
                        if (header.isEmpty()) break
                        put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
                    } }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val bodyBytes = ByteArray(length)
                    var offset = 0
                    while (offset < length) { val read = input.read(bodyBytes, offset, length - offset); if (read < 0) break; offset += read }
                    requests += Captured(line[0], line[1], headers["authorization"], bodyBytes.toString(Charsets.UTF_8))
                    val body = when (line[1].substringBefore('?')) {
                        "/api/v1/admin/overview" -> """{"version":"2.1.0","host":"127.0.0.1","port":8080,"dataDirectory":"/fixture","tokenCount":1,"serviceCount":0,"onlineServiceCount":0}"""
                        "/api/v1/admin/tokens" -> if (line[0] == "POST") """{"id":"created","token":"new-test-secret","label":"手机专用","role":"user"}""" else {
                            if (holdTokens) { input.read(); cancelled = true; return@thread }
                            """[{"id":"current","label":"当前管理员","role":"admin"}]"""
                        }
                        "/api/v1/admin/services" -> "[]"
                        "/api/v1/admin/settings" -> """{"maxUploadBytes":10485760,"maxDownloadBytes":10485760}"""
                        "/api/v1/admin/runtime", "/api/v1/admin/restart" -> """{"currentVersion":"2.0.1","latestVersion":"2.1.0","updateAvailable":true,"updateSupported":true,"restartSupported":true,"operation":null}"""
                        else -> error("Unexpected ${line[1]}")
                    }
                    val bytes = "{\"data\":$body}".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush()
                    }
                } } catch (_: Exception) { } finally { sockets -= socket } }
            }
        } }
        private fun readLine(input: java.io.InputStream): String {
            val line = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte < 0 || byte == 10) break
                if (byte != 13) line.write(byte)
            }
            return line.toString(Charsets.US_ASCII.name())
        }
        override fun close() { server.close(); sockets.forEach(Socket::close) }
    }
}
