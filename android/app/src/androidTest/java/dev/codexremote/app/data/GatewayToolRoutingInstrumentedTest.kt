package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GatewayToolRoutingInstrumentedTest {
    @Test fun codexKeepsItsExistingRequestWithoutToolNegotiation() = runBlocking {
        serve(listOf(200 to "{\"data\":{\"version\":\"1\"}}")) { api, requests ->
            assertEquals("1", api.extensionStatus("server").getString("version"))
            assertTrue(requests.single().startsWith("GET /api/v3/services/server/extension/status HTTP/1.1"))
            assertFalse(requests.single().contains("toolId"))
        }
    }

    @Test fun claudeBindsStatusAndManagementToItsToolWithoutRepeatingSuccessfulNegotiation() = runBlocking {
        val capabilities = "{\"data\":{\"capabilities\":[\"extension-tools-v1\",\"extension-window-management-v1\"]}}"
        val status = "{\"data\":{\"version\":\"1\",\"toolId\":\"claude\"}}"
        serve(listOf(200 to capabilities, 200 to status, 200 to status, 200 to status,
            200 to capabilities, 200 to "{\"data\":{\"stopped\":true}}")) { api, requests ->
            api.extensionStatus("server", "claude")
            api.updateExtension("server", "claude")
            api.applyExtension("server", confirmed = true, toolId = "claude")
            api.closeExtensionWindow("server", "431051c5-fdb5-43aa-b157-16ca55f0b5ed", "/project", "claude")
            assertEquals(6, requests.size)
            assertTrue(requests[1].startsWith("GET /api/v3/services/server/extension/status?toolId=claude HTTP/1.1"))
            for (index in listOf(2, 3, 5)) {
                val body = JSONObject(requests[index].substringAfter("\r\n\r\n"))
                assertEquals("claude", body.getString("toolId"))
            }
            assertTrue(JSONObject(requests[3].substringAfter("\r\n\r\n")).getBoolean("confirmed"))
            assertEquals("/project", JSONObject(requests[5].substringAfter("\r\n\r\n")).getString("workspace"))
        }
    }

    @Test fun olderGatewayRejectsClaudeBeforeSendingBusinessRequests() = runBlocking {
        serve(listOf(200 to "{\"data\":{\"capabilities\":[]}}")) { api, requests ->
            val failure = runCatching { api.extensionStatus("server", "claude") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("升级 Gateway"))
            assertEquals(1, requests.size)
        }
    }

    @Test fun cachedCapabilityDoesNotAllowClaudeToReceiveCodexStatus() = runBlocking {
        serve(listOf(
            200 to "{\"data\":{\"capabilities\":[\"extension-tools-v1\"]}}",
            200 to "{\"data\":{\"version\":\"1\",\"toolId\":\"claude\"}}",
            200 to "{\"data\":{\"version\":\"1\"}}",
        )) { api, requests ->
            assertEquals("claude", api.extensionStatus("server", "claude").getString("toolId"))
            val failure = runCatching { api.extensionStatus("server", "claude") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("未确认工具身份"))
            assertEquals(3, requests.size)
        }
    }

    @Test fun forcedNegotiationRejectsAnOlderGatewayAfterPriorSuccess() = runBlocking {
        serve(listOf(
            200 to "{\"data\":{\"capabilities\":[\"extension-tools-v1\"]}}",
            200 to "{\"data\":{\"capabilities\":[]}}",
        )) { api, requests ->
            api.ensureExtensionTool("claude")
            val failure = runCatching { api.ensureExtensionTool("claude", force = true) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("升级 Gateway"))
            assertEquals(2, requests.size)
        }
    }

    @Test fun oldAgentResponseClearlyRequestsAnUpgrade() = runBlocking {
        serve(listOf(
            200 to "{\"data\":{\"capabilities\":[\"extension-tools-v1\"]}}",
            409 to "{\"error\":{\"code\":\"UPGRADE_AGENT_REQUIRED\",\"message\":\"upgrade\"}}",
        )) { api, _ ->
            val failure = runCatching { api.extensionStatus("server", "claude") }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("升级该服务器的 Agent"))
        }
    }

    private suspend fun serve(responses: List<Pair<Int, String>>, block: suspend (GatewayApi, List<String>) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val requests = Collections.synchronizedList(mutableListOf<String>())
            val failure = AtomicReference<Throwable?>(null)
            val worker = thread(isDaemon = true, name = "tool-routing-test") {
                try {
                    for ((code, body) in responses) server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers += line
                        }
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val content = CharArray(length)
                        var offset = 0
                        while (offset < content.size) {
                            val read = reader.read(content, offset, content.size - offset)
                            check(read > 0)
                            offset += read
                        }
                        requests += headers.joinToString("\r\n") + "\r\n\r\n" + String(content)
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
            }
            val api = GatewayApi().apply { configure(GatewayConfig("http://127.0.0.1:${server.localPort}", "test-owner", allowHttp = true)) }
            try { block(api, requests) }
            finally {
                api.clear()
                server.close()
                worker.join(2000)
            }
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }
}
