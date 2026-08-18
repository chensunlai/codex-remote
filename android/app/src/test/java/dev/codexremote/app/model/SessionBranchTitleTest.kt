package dev.codexremote.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionBranchTitleTest {
    @Test
    fun numbersBranchesAcrossTheWholeForkTree() {
        val root = session("root", "Implement feature")
        val second = session("second", "Implement feature (2)", "root")
        val third = session("third", "Implement feature (3)", "second")

        assertEquals("Implement feature (4)", nextBranchTitle(second, listOf(root, second, third)))
    }

    @Test
    fun ignoresAnUnrelatedThreadWithTheSameTitle() {
        val root = session("root", "Implement feature")
        val unrelated = session("other", "Implement feature (8)")

        assertEquals("Implement feature (2)", nextBranchTitle(root, listOf(root, unrelated)))
    }

    @Test
    fun continuesNumberingWhenTheParentIsOutsideTheLoadedPage() {
        val branch = session("branch", "Implement feature (2)", "missing-parent")

        assertEquals("Implement feature (3)", nextBranchTitle(branch, listOf(branch)))
    }

    @Test
    fun limitsTheGeneratedTitleToSixtyCharacters() {
        val result = nextBranchTitle(session("root", "a".repeat(80)), emptyList())

        assertEquals(60, result?.length)
        assertEquals(" (2)", result?.takeLast(4))
    }

    @Test
    fun leavesAnUntitledThreadUnnamed() {
        assertNull(nextBranchTitle(session("root", ""), emptyList()))
    }

    private fun session(id: String, title: String, forkedFromId: String? = null) =
        SessionSummary(
            id = id,
            name = title.takeIf(String::isNotEmpty),
            preview = "",
            cwd = "/workspace",
            updatedAt = 1,
            status = "idle",
            isPinned = false,
            forkedFromId = forkedFromId,
        )
}
