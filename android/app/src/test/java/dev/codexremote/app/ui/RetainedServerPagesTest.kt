package dev.codexremote.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RetainedServerPagesTest {
    private val available = setOf("a", "b", "c", "d")

    @Test fun shrinkingWhileInSettingsKeepsCurrentPageEvenIfItWasOldest() {
        assertEquals(listOf("a"), retainedServerPageIds(listOf("a", "b", "c"), available, "a", false, 1))
    }

    @Test fun switchingBeyondCapacityEvictsLeastRecentlyVisitedAndKeepsOrder() {
        var pages = listOf("a", "b", "c")
        pages = retainedServerPageIds(pages, available, "a", true, 3)
        assertEquals(listOf("b", "c", "a"), pages)
        assertEquals(listOf("c", "a", "d"), retainedServerPageIds(pages, available, "d", true, 3))
    }

    @Test fun largerLimitDoesNotPreloadUnvisitedServersAndRemovedServersAreEvicted() {
        assertEquals(listOf("a"), retainedServerPageIds(listOf("a"), available, "d", false, 10))
        assertEquals(listOf("b"), retainedServerPageIds(listOf("a", "b"), setOf("b"), "a", true, 10))
        assertEquals(emptyList<String>(), retainedServerPageIds(emptyList(), available, null, true, 3))
    }
}
