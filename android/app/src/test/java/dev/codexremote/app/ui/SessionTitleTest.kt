package dev.codexremote.app.ui

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.SessionSummary
import dev.codexremote.app.model.ThreadDetail
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTitleTest {
    @Test
    fun usesListPreviewWhenReopenedThreadHasNoExplicitName() {
        val thread = thread()
        val summary = SessionSummary(
            id = thread.id,
            name = null,
            preview = "修复构建错误",
            cwd = thread.cwd,
            updatedAt = 1,
            status = "idle",
            isPinned = false,
        )

        assertEquals("修复构建错误", thread.displayTitle(listOf(summary)))
    }

    @Test
    fun usesFirstUserMessageBeforeFallingBackToUnnamed() {
        val thread = thread().copy(
            messages = listOf(
                ChatMessage("assistant", MessageRole.ASSISTANT, "你好"),
                ChatMessage("user", MessageRole.USER, "第一行标题\n后续内容"),
            ),
        )

        assertEquals("第一行标题", thread.displayTitle(emptyList()))
    }

    @Test
    fun explicitNameWinsOverGeneratedTitles() {
        val thread = thread().copy(name = "固定名称")
        val summary = SessionSummary(
            id = thread.id,
            name = null,
            preview = "自动标题",
            cwd = thread.cwd,
            updatedAt = 1,
            status = "idle",
            isPinned = false,
        )

        assertEquals("固定名称", thread.displayTitle(listOf(summary)))
    }

    private fun thread() = ThreadDetail(
        id = "thread-1",
        name = null,
        cwd = "/workspace/project",
        status = "idle",
        messages = emptyList(),
        activeTurnId = null,
    )
}
