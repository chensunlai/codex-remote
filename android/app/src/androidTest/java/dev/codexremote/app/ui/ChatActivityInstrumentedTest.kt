package dev.codexremote.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.TurnSummary
import org.junit.Rule
import org.junit.Test

class ChatActivityInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun completedActivityCollapsesAndExpandsLikeCodex() {
        val command = ChatMessage(
            id = "command-1",
            role = MessageRole.TOOL,
            text = "rg TODO src",
            status = "completed",
            kind = "commandExecution",
            detail = "src/App.kt:1: TODO",
            turnId = "turn-1",
        )
        val turn = TurnSummary(
            id = "turn-1",
            status = "completed",
            durationMs = 2_000,
        )

        compose.setContent {
            CodexRemoteTheme {
                ActivityGroup(listOf(command), turn, isActiveTurn = false)
            }
        }

        compose.onNodeWithText("运行了命令").assertIsDisplayed()
        compose.onAllNodesWithText("已运行 rg TODO src").assertCountEquals(0)
        compose.onNodeWithText("已处理 2.0 秒").performClick()
        compose.onNodeWithText("已运行 rg TODO src").assertIsDisplayed()
    }

    @Test
    fun activeActivityAndIdleGapUseThinkingLabels() {
        val command = ChatMessage(
            id = "command-1",
            role = MessageRole.TOOL,
            text = "npm test",
            status = "inProgress",
            kind = "commandExecution",
            turnId = "turn-1",
        )

        compose.setContent {
            CodexRemoteTheme {
                androidx.compose.foundation.layout.Column {
                    ActivityGroup(
                        messages = listOf(command),
                        turn = TurnSummary("turn-1", "inProgress"),
                        isActiveTurn = true,
                    )
                    ThinkingIndicator()
                }
            }
        }
        compose.onNodeWithText("正在运行 npm test").assertIsDisplayed()
        compose.onNodeWithText("正在思考").assertIsDisplayed()
    }
}
