package dev.codexremote.app.model

import org.junit.Assert.*
import org.junit.Test

class CodingToolTest {
    private val linux = RemoteService("server", "Server", null, "linux", null, null, "/home/user", RuntimeState.CONNECTED, null)

    @Test fun claudeOnlySupportsTheInitialLinuxTarget() {
        assertTrue(CodingTool.CLAUDE.supports(linux))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = "win32")))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = "darwin")))
        assertFalse(CodingTool.CLAUDE.supports(linux.copy(platform = null)))
        assertTrue(CodingTool.CODEX.supports(linux.copy(platform = "win32")))
    }

    @Test fun unknownToolsDoNotSilentlyUseCodex() {
        assertEquals(CodingTool.CLAUDE, CodingTool.fromId("claude"))
        assertNull(CodingTool.fromId("unknown"))
    }
}
