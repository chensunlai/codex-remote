package dev.codexremote.app

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMergeTest {
    @Test
    fun skipsOptimisticUserMessageWhenRealtimeItemAlreadyArrived() {
        val realtime = ChatMessage(
            id = "user-item",
            role = MessageRole.USER,
            text = "hello",
            turnId = "turn-1",
        )
        val optimistic = ChatMessage(
            id = "local-1",
            role = MessageRole.USER,
            text = "hello",
            turnId = "turn-1",
        )

        assertEquals(listOf(realtime), listOf(realtime).withOptimisticUserMessage(optimistic))
    }

    @Test
    fun retainsSameTextFromDifferentTurns() {
        val previous = ChatMessage(
            id = "user-item-1",
            role = MessageRole.USER,
            text = "hello",
            turnId = "turn-1",
        )
        val next = ChatMessage(
            id = "local-2",
            role = MessageRole.USER,
            text = "hello",
            turnId = "turn-2",
        )

        assertEquals(2, listOf(previous).withOptimisticUserMessage(next).size)
    }

    @Test
    fun replacesOptimisticUserMessageWhenRealtimeItemArrivesLater() {
        val optimistic = ChatMessage(
            id = "local-1",
            role = MessageRole.USER,
            text = "hello",
            status = "inProgress",
            turnId = "turn-1",
        )
        val realtime = ChatMessage(
            id = "user-item",
            role = MessageRole.USER,
            text = "hello",
            status = "inProgress",
            turnId = "turn-1",
        )

        assertEquals(listOf(realtime), listOf(optimistic).withRealtimeMessage(realtime))
    }

    @Test
    fun retainsOptimisticUserMessageFromAnotherTurn() {
        val previous = ChatMessage(
            id = "local-1",
            role = MessageRole.USER,
            text = "hello",
            status = "inProgress",
            turnId = "turn-1",
        )
        val realtime = ChatMessage(
            id = "user-item",
            role = MessageRole.USER,
            text = "hello",
            status = "inProgress",
            turnId = "turn-2",
        )

        assertEquals(2, listOf(previous).withRealtimeMessage(realtime).size)
    }
}
