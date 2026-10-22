package dev.codexremote.app.model

import org.junit.Assert.*
import org.junit.Test

class CodingToolTest {
    private val linux = RemoteService("server", "Server", null, "linux", null, null, "/home/user", RuntimeState.CONNECTED, null)

    @Test fun claudePreservesLinuxCompatibilityWithoutEnablingWindowsOrUnknownPlatforms() {
        assertTrue(CodingTool.CLAUDE.supports(linux))
        assertTrue(CodingTool.CLAUDE.supports(linux.copy(capabilities = emptySet())))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = "win32")))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = "win32", capabilities = setOf("claude-extension-host-v1"))))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = null)))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = null, capabilities = setOf("claude-extension-host-v1"))))
        assertTrue(CodingTool.CODEX.supports(linux.copy(platform = "win32")))
        assertTrue(CodingTool.CODEX.supports(linux.copy(platform = "darwin")))
    }

    @Test fun claudeOnMacRequiresAnAgentThatAdvertisesTheHostCapability() {
        val mac = linux.copy(platform = "darwin", home = "/Users/user")
        assertFalse(CodingTool.CLAUDE.supports(mac))
        assertFalse(CodingTool.CLAUDE.supports(mac.copy(capabilities = emptySet())))
        assertFalse(CodingTool.CLAUDE.supports(mac.copy(capabilities = setOf("codex-extension-host-v1", "terminal"))))
        assertTrue(CodingTool.CLAUDE.supports(mac.copy(capabilities = setOf("claude-extension-host-v1", "terminal"))))
    }

    @Test fun unknownToolsDoNotSilentlyUseCodex() {
        assertEquals(CodingTool.CLAUDE, CodingTool.fromId("claude"))
        assertNull(CodingTool.fromId("unknown"))
    }
}
