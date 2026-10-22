package dev.codexremote.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.WorkspaceWindow
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

class WorkspaceWindowToolsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private val services = parseServices(JSONArray("""[
        {"id":"linux","name":"Linux fixture","platform":"linux","home":"/home/fixture","runtime":{"state":"connected"}},
        {"id":"windows","name":"Windows fixture","platform":"win32","home":"C:\\Users\\Fixture","runtime":{"state":"connected"}}
    ]"""))

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = object : Application() {
            private val prefix = "window-tools-${UUID.randomUUID()}"
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        // No saved Gateway and a supplied home directory keep this UI fixture offline.
        compose.runOnUiThread { model = MainViewModel(application).also { store.put("fixture", it) } }
    }

    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun toolChoicesStayExclusiveAcrossThemesAndUnsupportedServerCannotUseClaude() {
        val theme = mutableStateOf(AppTheme.DARK)
        compose.setContent {
            CodexRemoteTheme(theme = theme.value) {
                NewWorkspaceWindowDialog(AppState(services = services, selectedServiceId = "linux"), model, {}, {})
            }
        }
        val codex = compose.onNode(hasText("Codex") and isSelectable())
        val claude = compose.onNode(hasText("Claude") and isSelectable())
        AppTheme.entries.forEach { candidate ->
            compose.runOnIdle { theme.value = candidate }
            codex.assertIsSelected()
            claude.assertIsNotSelected().assertIsEnabled().performClick()
            claude.assertIsSelected().performClick().assertIsSelected()
            screenshot("new-window-${candidate.id}", dialog = true)
            codex.assertIsNotSelected().performClick().assertIsSelected()
            claude.assertIsNotSelected()
        }
        compose.onNodeWithText("首次使用时按需下载", substring = true).assertDoesNotExist()
        claude.performClick()
        compose.onNodeWithContentDescription("选择服务器").performClick()
        compose.onNodeWithText("Windows fixture").performClick()
        claude.assertIsNotEnabled()
        compose.onNodeWithText("打开窗口").assertIsNotEnabled()
        codex.assertIsEnabled().performClick()
        compose.onNodeWithText("打开窗口").assertIsEnabled()
    }

    @Test fun windowDropdownShowsRecentWindowsFirstAndNewOnlyOpensCreation() {
        val windows = listOf(
            WorkspaceWindow("one", "linux", "/home/fixture", "Work one", "codex"),
            WorkspaceWindow("two", "linux", "/home/fixture", "Work two", "claude"),
            WorkspaceWindow("three", "linux", "/home/other", "Work three", "codex"),
            WorkspaceWindow("offline", "offline", "/home/fixture", "Offline work", "codex"),
            WorkspaceWindow("missing", "missing", "/home/fixture", "Missing server", "claude"),
        )
        val current = mutableStateOf(windows[0])
        val recent = mutableStateOf(listOf("one", "offline", "three", "missing", "two"))
        val listedServices = services + services.first().copy(id = "offline", runtimeState = RuntimeState.DISCONNECTED)
        var selected: String? = null
        var newCalls = 0
        compose.setContent {
            CodexRemoteTheme {
                Box(Modifier.requiredWidth(360.dp)) {
                    WorkPageSelector(AppState(services = listedServices, windows = windows, windowMode = true,
                        selectedWindowId = current.value.id, recentWindowIds = recent.value),
                        current.value, true, setOf("two"),
                        onCreateWindow = { newCalls++ }, onSelectService = {},
                        onSelectWindow = { id -> selected = id; current.value = windows.first { it.id == id }
                            recent.value = listOf(id) + recent.value.filterNot { it == id } },
                        onCloseWindow = {}, onSwitchTool = {})
                }
            }
        }
        compose.onNodeWithText("Work one").assertIsDisplayed()
        compose.onNodeWithText(" ${windows.size}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Codex", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Work one").performClick()
        fun row(title: String) = compose.onNode(hasText(title) and hasAnyAncestor(isPopup()))
        row("Offline work").assertDoesNotExist()
        row("Missing server").assertDoesNotExist()
        // A connected server without a created window is not a window-list entry.
        row("Windows fixture").assertDoesNotExist()
        val first = row("Work one").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val second = row("Work three").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val third = row("Work two").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < second.top && second.top < third.top)
        compose.onNode(hasAnyAncestor(isPopup()) and hasContentDescription("Claude Code"),
            useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("WebView 已保留", useUnmergedTree = true).assertExists()
        screenshot("window-dropdown", popup = true)
        row("Work three").performClick()
        compose.runOnIdle { assertEquals("three", selected); assertEquals(0, newCalls) }
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNodeWithText("Work three").performClick()
        assertTrue(row("Work three").fetchSemanticsNode().boundsInRoot.top < row("Work one").fetchSemanticsNode().boundsInRoot.top)
        row("新建").performClick()
        compose.runOnIdle { assertEquals(1, newCalls); assertEquals("three", selected) }
        compose.onNode(isPopup()).assertDoesNotExist()
    }

    @Test fun closeButtonTargetsItsWindowWithoutSelectingOrOpeningCreationAndDisablesWhileBusy() {
        val windows = mutableStateOf(listOf(
            WorkspaceWindow("one", "linux", "/home/fixture", "Work one", "codex"),
            WorkspaceWindow("two", "linux", "/home/fixture", "Work two", "claude"),
            WorkspaceWindow("three", "linux", "/home/other", "Work three", "codex"),
        ))
        val busy = mutableStateOf(setOf("two"))
        var selected: String? = null
        var closed: String? = null
        var newCalls = 0
        compose.setContent {
            CodexRemoteTheme {
                WorkPageSelector(AppState(services = services, windows = windows.value, windowMode = true,
                    busyWindowIds = busy.value), windows.value.first(), true, setOf("three"),
                    onCreateWindow = { newCalls++ }, onSelectService = {},
                    onSelectWindow = { selected = it },
                    onCloseWindow = { id -> closed = id; windows.value = windows.value.filterNot { it.id == id } },
                    onSwitchTool = {})
            }
        }
        compose.onNodeWithText("Work one").performClick()
        compose.onNodeWithContentDescription("关闭窗口 Work two").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭窗口 Work three").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("three", closed)
            assertEquals(null, selected)
            assertEquals(0, newCalls)
            busy.value = emptySet()
        }
        compose.onNode(isPopup()).assertExists()
        compose.onNodeWithText("Work three").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭窗口 Work two").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("two", closed); assertEquals(null, selected) }
        compose.onNode(isPopup()).assertExists()
    }

    @Test fun offlineWindowsDisappearAndReappearWhenTheirServerReconnects() {
        val windows = listOf(
            WorkspaceWindow("one", "linux", "/home/fixture", "Online work", "codex"),
            WorkspaceWindow("two", "windows", "C:\\Users\\Fixture", "Offline work", "codex"),
        )
        val listedServices = mutableStateOf(services)
        var selected: String? = null
        compose.setContent {
            CodexRemoteTheme {
                WorkPageSelector(AppState(services = listedServices.value, windows = windows, windowMode = true),
                    windows.first(), true, emptySet(), onCreateWindow = {}, onSelectService = {},
                    onSelectWindow = { selected = it }, onCloseWindow = {}, onSwitchTool = {})
            }
        }
        compose.onNodeWithText("Online work").performClick()
        compose.onNodeWithText("Offline work").assertIsDisplayed()
        compose.runOnIdle { listedServices.value = services.map {
            if (it.id == "windows") it.copy(runtimeState = RuntimeState.DISCONNECTED) else it
        } }
        compose.onNodeWithText("Offline work").assertDoesNotExist()
        compose.onNode(isPopup()).assertExists()
        compose.runOnIdle { listedServices.value = services }
        compose.onNodeWithText("Offline work").assertIsDisplayed()
        compose.onNodeWithText("Offline work").performClick()
        compose.runOnIdle { assertEquals("two", selected) }
    }

    @Test fun selectorShowsServerOrWindowAndDispatchesOnlyTheSelectedAction() {
        val windows = listOf(
            WorkspaceWindow("one", "linux", "/home/fixture", "Codex task", "codex"),
            WorkspaceWindow("two", "linux", "/home/fixture", "Claude task", "claude"),
            WorkspaceWindow("three", "windows", "C:\\Users\\Fixture", "Windows task", "codex"),
        )
        val current = mutableStateOf(windows[0])
        val windowMode = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var switchCalls = 0
        var newCalls = 0
        var selectedService: String? = null
        compose.setContent {
            CodexRemoteTheme {
                Box(Modifier.requiredWidth(360.dp)) {
                    WorkPageSelector(AppState(services = services, windows = windows, windowMode = windowMode.value),
                        current.value, enabled.value, setOf("one", "two"),
                        onCreateWindow = { newCalls++ },
                        onSelectService = { id -> selectedService = id; current.value = windows.first { it.serviceId == id } },
                        onSelectWindow = { id -> current.value = windows.first { it.id == id } },
                        onCloseWindow = {},
                        onSwitchTool = { switchCalls++; current.value = windows[1] })
                }
            }
        }
        compose.onNodeWithText("Linux fixture").assertIsDisplayed()
        compose.onNodeWithText("Codex task").assertDoesNotExist()
        compose.onNodeWithContentDescription("Codex", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("切换到 Claude Code").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, switchCalls) }
        compose.onNodeWithContentDescription("切换到 Codex").assertIsEnabled()
        compose.onNodeWithContentDescription("切换到 Claude Code").assertDoesNotExist()
        compose.onNodeWithText("Linux fixture").performClick()
        compose.onNodeWithContentDescription("WebView 已保留", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Windows fixture").performClick()
        compose.runOnIdle { assertEquals("windows", selectedService); assertEquals(1, switchCalls) }
        compose.onNodeWithContentDescription("切换到 Claude Code").assertIsNotEnabled()
        compose.runOnIdle { current.value = windows[1]; windowMode.value = true }
        compose.onNodeWithText("Claude task").assertIsDisplayed()
        compose.onNodeWithText("Linux fixture").assertDoesNotExist()
        compose.onNodeWithContentDescription("Claude Code", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription("切换到 Codex").assertDoesNotExist()
        compose.onNodeWithText("Claude task").performClick()
        compose.onNode(hasText("Codex task") and hasAnyAncestor(isPopup())).performClick()
        compose.runOnIdle { assertEquals(0, newCalls); assertEquals(windows[0], current.value); enabled.value = false }
        compose.onNodeWithText("Codex task").assertIsNotEnabled()
    }

    @Test fun compactActualToolbarKeepsActionsVisibleInBothModesAndAllThemes() {
        val window = WorkspaceWindow("one", "linux", "/home/fixture", "Review changes", "codex")
        val theme = mutableStateOf(AppTheme.DARK)
        val windowMode = mutableStateOf(false)
        val appState = AppState(
            services = services.map { if (it.id == "linux") it.copy(name = "2A800-chen") else it },
            windows = listOf(window), selectedWindowId = window.id,
            // Deliberately not configured in the ViewModel: requests fail locally, with no network or host launch.
            gatewayConfig = GatewayConfig("https://fixture.invalid", "fixture-token"),
        )
        compose.setContent {
            CodexRemoteTheme(theme = theme.value) {
                Box(Modifier.requiredWidth(360.dp).fillMaxHeight()) {
                    OfficialChatScreen(appState.copy(windowMode = windowMode.value, theme = theme.value), model, window)
                }
            }
        }
        AppTheme.entries.forEach { candidate ->
            compose.runOnIdle { theme.value = candidate; windowMode.value = false }
            compose.onNodeWithText("2A800-chen").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到 Claude Code").assertIsDisplayed()
            compose.onNodeWithContentDescription("鼠标模式").assertIsDisplayed()
            compose.onNodeWithContentDescription("更改工作目录").assertIsDisplayed()
            compose.onNodeWithContentDescription("更多选项").assertIsDisplayed()
            screenshot("toolbar-server-${candidate.id}")
            compose.runOnIdle { windowMode.value = true }
            compose.onNodeWithText(window.title).assertIsDisplayed()
            compose.onNodeWithContentDescription("Codex", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription("更改工作目录").assertDoesNotExist()
            compose.onNodeWithContentDescription("切换到 Claude Code").assertDoesNotExist()
            screenshot("toolbar-windows-${candidate.id}")
        }
    }

    private fun screenshot(name: String, dialog: Boolean = false, popup: Boolean = false) {
        val target = when { dialog -> compose.onNode(isDialog()); popup -> compose.onNode(isPopup()); else -> compose.onRoot() }
        val bitmap = target.captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(directory, "ui-check-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
