package dev.codexremote.app.data

import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.WorkspaceWindow
import org.junit.Assert.*
import org.junit.Test

class WorkspaceWindowSelectionTest {
    private val first = WorkspaceWindow("first", "one", "/first", "First")
    private val second = WorkspaceWindow("second", "one", "/second", "Second")
    private val claude = WorkspaceWindow("claude", "one", "/claude", "Claude", "claude")
    private val other = WorkspaceWindow("other", "two", "/other", "Other")
    private val windows = listOf(first, second, claude, other)

    @Test fun defaultsToWindowModeWithFiveResidentPages() {
        assertTrue(AppState().windowMode)
        assertEquals(5, AppState().maxChatPages)
        assertEquals(5, UiPreferences.DEFAULT_MAX_CHAT_PAGES)
    }

    @Test fun defaultNamesNumberOnlyWindowsOfTheSameServerAndTool() {
        assertEquals("Server", defaultWorkspaceWindowTitle(emptyList(), "one", "codex", "Server"))
        assertEquals("Server", defaultWorkspaceWindowTitle(listOf(first), "one", "claude", "Server"))
        assertEquals("Server", defaultWorkspaceWindowTitle(listOf(first), "two", "codex", "Server"))
        assertEquals("Server 2", defaultWorkspaceWindowTitle(listOf(first), "one", "codex", "Server"))
        assertEquals("Server 3", defaultWorkspaceWindowTitle(listOf(first, second.copy(title = "Server 2")), "one", "codex", "Server"))
    }

    @Test fun serverAndToolSwitchesReuseTheMostRecentlySelectedMatchingWindow() {
        val recent = recentWorkspaceWindowIds(windows, listOf(first.id, claude.id), other.id)
        assertEquals(first, reusableWorkspaceWindow(windows, recent, other.id, "one", "codex"))
        assertEquals(claude, reusableWorkspaceWindow(windows, recent, first.id, "one", "claude"))
        assertEquals(first, reusableWorkspaceWindow(windows, recent, claude.id, "one", "codex"))
        assertNull(reusableWorkspaceWindow(windows, recent, claude.id, "two", "claude"))
    }

    @Test fun oldRecordsPreserveCurrentIdentityAndVisibleWindowOrder() {
        val recent = recentWorkspaceWindowIds(windows, emptyList(), first.id)
        assertEquals(first, reusableWorkspaceWindow(windows, recent, first.id, "one"))
        assertEquals(listOf(first, second, claude, other), windows)
        assertEquals(first.id, recent.first())
        assertEquals(windows.size, recent.size)
    }

    @Test fun closingAWindowUsesRemainingHistoryWithoutDanglingIds() {
        val recent = recentWorkspaceWindowIds(windows, listOf("removed", first.id, first.id), first.id)
        val remaining = windows.filterNot { it.id == first.id }
        val cleaned = recentWorkspaceWindowIds(remaining, recent, other.id)
        assertFalse(first.id in cleaned)
        assertFalse("removed" in cleaned)
        assertEquals(second, reusableWorkspaceWindow(remaining, cleaned, other.id, "one", "codex"))
    }
}
