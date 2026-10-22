package dev.codexremote.app.model

import org.junit.Assert.*
import org.junit.Test

class RemoteServiceTest {
    private fun service(platform: String?, capabilities: Set<String>?) = RemoteService(
        "fixture", "Fixture", null, platform, null, null, null, RuntimeState.CONNECTED, null, capabilities,
    )

    @Test fun windowsNeverEnablesTerminalAndOldLinuxServicesRemainCompatible() {
        assertFalse(service("win32", null).supportsTerminal)
        assertFalse(service("win32", setOf("terminal")).supportsTerminal)
        assertTrue(service("linux", null).supportsTerminal)
        assertTrue(service(null, null).supportsTerminal)
        assertTrue(service("linux", setOf("terminal", "filesystem")).supportsTerminal)
        assertFalse(service("linux", emptySet()).supportsTerminal)
        assertFalse(service("linux", setOf("filesystem")).supportsTerminal)
        assertTrue(service("darwin", null).supportsTerminal)
        assertTrue(service("darwin", setOf("terminal", "filesystem")).supportsTerminal)
        assertFalse(service("darwin", setOf("filesystem")).supportsTerminal)
    }
}
