package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.WorkspaceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WindowStateScopeTest {
    private val config = GatewayConfig("https://gateway.example", "credential")
    private val window = WorkspaceWindow("431051c5-fdb5-43aa-b157-16ca55f0b5ed", "server", "/workspace", "First")

    @Test fun windowsAndDirectoriesHaveIndependentOpaquePageState() {
        val scope = windowStateScope(config, window)
        assertNotEquals(scope, windowStateScope(config, window.copy(id = "e4ee92f0-1f13-4c7d-813f-c0789f8b0a60")))
        assertNotEquals(scope, windowStateScope(config, window.copy(workspace = "/another")))
        assertNotEquals(scope, windowStateScope(config, window.copy(serviceId = "another-server")))
        assertNotEquals(scope, windowStateScope(config.copy(token = "other-owner"), window))
        assertEquals(scope, windowStateScope(config, window.copy(title = "Renamed")))
    }

    @Test fun toolsShareResourcesOnlyWithinTheirToolAndKeepCodexLegacyIdentity() {
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${config.baseUrl}\u0000${config.token}\u0000${window.serviceId}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, frontendScope(config, window.serviceId))
        assertEquals(expected, frontendScope(config, window.serviceId, "codex"))
        assertNotEquals(expected, frontendScope(config, window.serviceId, "claude"))
        assertNotEquals(windowStateScope(config, window), windowStateScope(config, window.copy(toolId = "claude")))
        assertEquals(frontendScope(config, window.serviceId, "claude"),
            frontendScope(config, window.copy(id = "another-window").serviceId, "claude"))
    }

    @Test fun gatewayRoutingPolicyDoesNotChangeTheWindowOrResourceIdentity() {
        val routed = config.copy(allowHttp = true, multipleEndpoints = true, endpoints = listOf("http://192.168.1.2"))
        assertEquals(windowStateScope(config, window), windowStateScope(routed, window))
        assertEquals(frontendScope(config, window.serviceId), frontendScope(routed, window.serviceId))
    }
}
