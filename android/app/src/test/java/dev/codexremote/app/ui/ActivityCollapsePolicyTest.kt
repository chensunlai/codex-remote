package dev.codexremote.app.ui

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.TurnSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityCollapsePolicyTest {
    @Test
    fun keepsLiveAnalysisVisibleButCommandsCollapsed() {
        assertTrue(defaultActivityGroupExpanded(true, false))
        assertTrue(defaultActivityItemExpanded(true, "reasoning"))
        assertFalse(defaultActivityItemExpanded(true, "commandExecution"))
    }

    @Test
    fun collapsesTheWholeActivityWhenTheFinalAssistantStarts() {
        val messages = listOf(
            ChatMessage(
                id = "reasoning",
                role = MessageRole.SYSTEM,
                text = "正在分析",
                status = "completed",
                kind = "reasoning",
                turnId = "turn-1",
            ),
            ChatMessage(
                id = "answer",
                role = MessageRole.ASSISTANT,
                text = "最终回复",
                status = "inProgress",
                turnId = "turn-1",
            ),
        )

        assertTrue(hasFinalAssistantStarted(messages, "turn-1"))
        assertFalse(defaultActivityGroupExpanded(true, true))
        assertFalse(defaultActivityItemExpanded(false, "reasoning"))
    }

    @Test
    fun keepsTheFinalAssistantOutsideTheCollapsibleActivity() {
        val messages = listOf(
            ChatMessage("user", MessageRole.USER, "开始", turnId = "turn-1"),
            ChatMessage(
                id = "reasoning",
                role = MessageRole.SYSTEM,
                text = "分析",
                kind = "reasoning",
                phase = "commentary",
                turnId = "turn-1",
            ),
            ChatMessage("answer", MessageRole.ASSISTANT, "完成", turnId = "turn-1"),
        )

        val timeline = buildChatTimeline(
            messages,
            listOf(TurnSummary("turn-1", "completed")),
        )

        assertEquals(3, timeline.size)
        assertTrue(timeline[0] is ChatTimelineEntry.Message)
        assertTrue(timeline[1] is ChatTimelineEntry.Activity)
        assertTrue(timeline[2] is ChatTimelineEntry.Message)
        assertEquals(
            "answer",
            (timeline[2] as ChatTimelineEntry.Message).message.id,
        )
    }
}
