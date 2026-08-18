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
import androidx.compose.ui.test.performTextInput
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
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.PermissionProfile
import dev.codexremote.app.model.RemoteFileMatch
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.SkillOption
import dev.codexremote.app.model.TurnSummary
import org.junit.Assert.assertEquals
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
            detail = "running output",
            turnId = "turn-1",
        )
        val reasoning = ChatMessage(
            id = "reasoning-1",
            role = MessageRole.SYSTEM,
            text = "正在分析",
            status = "inProgress",
            kind = "reasoning",
            detail = "中间分析保持展开",
            turnId = "turn-1",
        )

        compose.setContent {
            CodexRemoteTheme {
                androidx.compose.foundation.layout.Column {
                    val itemExpansion = remember { mutableStateMapOf<String, Boolean>() }
                    ActivityGroup(
                        messages = listOf(command, reasoning),
                        turn = TurnSummary("turn-1", "inProgress"),
                        isActiveTurn = true,
                        expanded = true,
                        onExpandedChange = {},
                        itemExpanded = {
                            itemExpansion[it.id] ?: (it.kind != "commandExecution")
                        },
                        onItemExpandedChange = { message, value -> itemExpansion[message.id] = value },
                    )
                    ThinkingIndicator()
                }
            }
        }
        compose.onNodeWithText("正在运行 npm test").assertIsDisplayed()
        compose.onAllNodesWithText("running output").assertCountEquals(0)
        compose.onNodeWithText("中间分析保持展开").assertIsDisplayed()
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
                            itemExpanded = { itemExpansion[it.id] ?: false },
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

        compose.onAllNodesWithText("stable output").assertCountEquals(0)
        compose.onNodeWithText("正在运行 npm test").performClick()
        compose.onNodeWithText("stable output").assertIsDisplayed()
        compose.onNodeWithText("正在运行 npm test").performClick()
        compose.onAllNodesWithText("stable output").assertCountEquals(0)
        compose.onNodeWithTag("activity-list").performScrollToIndex(24)
        compose.onNodeWithTag("activity-list").performScrollToIndex(0)
        compose.onAllNodesWithText("stable output").assertCountEquals(0)
    }

    @Test
    fun addPaletteMatchesCodexActionsAndSearchesFiles() {
        var query = ""
        var selectedPath = ""
        compose.setContent {
            CodexRemoteTheme {
                TestComposerPalette(
                    panel = ComposerPanel.ADD,
                    fileQuery = query,
                    fileResults = listOf(
                        RemoteFileMatch("App.kt", "/workspace/src/App.kt", RemoteFileType.FILE),
                    ),
                    onFileQuery = { query = it },
                    onFile = { selectedPath = it.path },
                )
            }
        }

        compose.onNodeWithText("文件和文件夹").assertIsDisplayed()
        compose.onNodeWithText("目标").assertIsDisplayed()
        compose.onNodeWithText("计划模式").assertIsDisplayed()
        compose.onNodeWithText("输入内容搜索文件").performTextInput("app")
        compose.runOnIdle { assertEquals("app", query) }
        compose.onNodeWithText("App.kt").performClick()
        compose.runOnIdle { assertEquals("/workspace/src/App.kt", selectedPath) }
    }

    @Test
    fun slashPaletteNavigatesToSmoothModelAndEffortSelectors() {
        var panel by mutableStateOf(ComposerPanel.COMMANDS)
        var selectedModel = "gpt-fixture"
        var selectedEffort = "medium"
        compose.setContent {
            CodexRemoteTheme {
                TestComposerPalette(
                    panel = panel,
                    selectedModel = selectedModel,
                    selectedEffort = selectedEffort,
                    onOpenPanel = { panel = it },
                    onBack = { panel = ComposerPanel.COMMANDS },
                    onModel = { selectedModel = it },
                    onEffort = { selectedEffort = it },
                )
            }
        }

        compose.onNodeWithText("权限").assertIsDisplayed()
        compose.onNodeWithText("Reasoning").assertIsDisplayed()
        compose.onAllNodesWithText("推理").assertCountEquals(0)
        compose.onNodeWithText("状态").assertIsDisplayed()
        compose.onNodeWithText("Image Gen").assertIsDisplayed()
        compose.onNodeWithText("模型").performClick()
        compose.onNodeWithText("GPT Fixture Fast").assertIsDisplayed()
        compose.onNodeWithText("GPT Fixture Fast").performClick()
        compose.runOnIdle { assertEquals("gpt-fixture-fast", selectedModel) }

        compose.runOnIdle { panel = ComposerPanel.EFFORTS }
        compose.onNodeWithText("Reasoning effort").assertIsDisplayed()
        compose.onNodeWithText("xhigh").assertIsDisplayed()
        compose.onAllNodesWithText("极高").assertCountEquals(0)
        compose.onNodeWithText("xhigh").performClick()
        compose.runOnIdle { assertEquals("xhigh", selectedEffort) }
    }
}

@androidx.compose.runtime.Composable
private fun TestComposerPalette(
    panel: ComposerPanel,
    fileQuery: String = "",
    fileResults: List<RemoteFileMatch> = emptyList(),
    selectedModel: String = "gpt-fixture",
    selectedEffort: String = "medium",
    onBack: () -> Unit = {},
    onFileQuery: (String) -> Unit = {},
    onFile: (RemoteFileMatch) -> Unit = {},
    onOpenPanel: (ComposerPanel) -> Unit = {},
    onModel: (String) -> Unit = {},
    onEffort: (String) -> Unit = {},
) {
    val models = listOf(
        ModelOption(
            id = "gpt-fixture",
            displayName = "GPT Fixture",
            efforts = listOf("low", "medium", "high", "xhigh", "max"),
            defaultEffort = "medium",
            isDefault = true,
        ),
        ModelOption(
            id = "gpt-fixture-fast",
            displayName = "GPT Fixture Fast",
            efforts = listOf("low", "medium", "high", "xhigh"),
            defaultEffort = "medium",
            isDefault = false,
        ),
    )
    ComposerPalette(
        panel = panel,
        slashQuery = "",
        fileQuery = fileQuery,
        fileResults = fileResults,
        models = models,
        selectedModel = selectedModel,
        selectedEffort = selectedEffort,
        permissionProfiles = listOf(PermissionProfile(":workspace", "Workspace access", true)),
        selectedPermission = ":workspace",
        skills = listOf(SkillOption("imagegen", "Image Gen", "Generate images", "/skills/imagegen", true)),
        threadId = "thread-fixture",
        tokenUsage = null,
        rateLimits = null,
        planAvailable = true,
        planEnabled = false,
        turnActive = false,
        onBack = onBack,
        onFileQuery = onFileQuery,
        onBrowseFiles = {},
        onFile = onFile,
        onGoal = {},
        onTogglePlan = {},
        onModel = onModel,
        onEffort = onEffort,
        onPermission = {},
        onSkill = {},
        onNew = {},
        onCompact = {},
        onReview = {},
        onOpenPanel = onOpenPanel,
    )
}
