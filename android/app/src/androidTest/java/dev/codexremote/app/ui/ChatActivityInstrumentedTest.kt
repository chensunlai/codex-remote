package dev.codexremote.app.ui

import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
                var expanded by remember { mutableStateOf(false) }
                val itemExpansion = remember { mutableStateMapOf<String, Boolean>() }
                ActivityGroup(
                    messages = listOf(command),
                    turn = turn,
                    isActiveTurn = false,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    itemExpanded = { itemExpansion[it.id] ?: false },
                    onItemExpandedChange = { message, value -> itemExpansion[message.id] = value },
                )
            }
        }

        compose.onNodeWithText("运行了命令").assertIsDisplayed()
        compose.onAllNodesWithText("已运行 rg TODO src").assertCountEquals(0)
        compose.onNodeWithText("已处理 2.0 秒").performClick()
        compose.onNodeWithText("已运行 rg TODO src").assertIsDisplayed()
        compose.onAllNodesWithText("src/App.kt:1: TODO").assertCountEquals(0)
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
                    val itemExpansion = remember { mutableStateMapOf<String, Boolean>() }
                    ActivityGroup(
                        messages = listOf(command),
                        turn = TurnSummary("turn-1", "inProgress"),
                        isActiveTurn = true,
                        expanded = true,
                        onExpandedChange = {},
                        itemExpanded = { itemExpansion[it.id] ?: true },
                        onItemExpandedChange = { message, value -> itemExpansion[message.id] = value },
                    )
                    ThinkingIndicator()
                }
            }
        }
        compose.onNodeWithText("正在运行 npm test").assertIsDisplayed()
        compose.onNodeWithText("正在思考").assertIsDisplayed()
    }

    @Test
    fun collapsedCommandDetailSurvivesLazyListRecycling() {
        val command = ChatMessage(
            id = "command-stable",
            role = MessageRole.TOOL,
            text = "npm test",
            status = "inProgress",
            kind = "commandExecution",
            detail = "stable output",
            turnId = "turn-stable",
        )

        compose.setContent {
            CodexRemoteTheme {
                val itemExpansion = remember { mutableStateMapOf<String, Boolean>() }
                androidx.compose.foundation.lazy.LazyColumn(
                    Modifier.height(180.dp).testTag("activity-list"),
                ) {
                    item {
                        ActivityGroup(
                            messages = listOf(command),
                            turn = TurnSummary("turn-stable", "inProgress"),
                            isActiveTurn = true,
                            expanded = true,
                            onExpandedChange = {},
                            itemExpanded = { itemExpansion[it.id] ?: it.status == "inProgress" },
                            onItemExpandedChange = { message, value -> itemExpansion[message.id] = value },
                        )
                    }
                    items(24) { index ->
                        androidx.compose.material3.Text(
                            "占位项 $index",
                            modifier = Modifier.height(72.dp),
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("stable output").assertIsDisplayed()
        compose.onNodeWithText("正在运行 npm test").performClick()
        compose.onAllNodesWithText("stable output").assertCountEquals(0)
        compose.onNodeWithTag("activity-list").performScrollToIndex(24)
        compose.onNodeWithTag("activity-list").performScrollToIndex(0)
        compose.onAllNodesWithText("stable output").assertCountEquals(0)
    }
}
