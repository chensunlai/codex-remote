package dev.codexremote.app.ui

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole

internal fun defaultActivityGroupExpanded(
    isTurnInProgress: Boolean,
    hasFinalAssistantStarted: Boolean,
): Boolean = isTurnInProgress && !hasFinalAssistantStarted

internal fun defaultActivityItemExpanded(
    isLiveActivity: Boolean,
    kind: String?,
): Boolean = isLiveActivity && kind != "commandExecution"

internal fun hasFinalAssistantStarted(messages: List<ChatMessage>, turnId: String?): Boolean =
    turnId != null && messages.any { message ->
        message.turnId == turnId &&
            message.role == MessageRole.ASSISTANT &&
            message.phase != "commentary" &&
            (message.text.isNotBlank() || message.status.isRunningStatus())
    }

internal fun String?.isRunningStatus(): Boolean =
    this in setOf("inProgress", "in_progress", "active")
