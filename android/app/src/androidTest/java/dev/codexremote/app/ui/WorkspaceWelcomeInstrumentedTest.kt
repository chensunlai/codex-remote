package dev.codexremote.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.WorkspaceWindow
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class WorkspaceWelcomeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private val services = parseServices(JSONArray("""[
        {"id":"windows","name":"Windows fixture","platform":"win32","home":"C:\\Users\\Fixture","runtime":{"state":"connected"}},
        {"id":"linux","name":"Linux fixture","platform":"linux","home":"/home/fixture","runtime":{"state":"connected"}}
    ]"""))
    private val normal = AppState(
        configured = true,
        gatewayConfig = GatewayConfig("https://fixture.invalid", "fixture-token"),
        services = services,
        selectedServiceId = "windows",
    )

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = object : Application() {
            private val prefix = "workspace-welcome-${UUID.randomUUID()}"
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        // The ViewModel has no saved Gateway; supplied homes avoid any network or host startup.
        compose.runOnUiThread { model = MainViewModel(application).also { store.put("fixture", it) } }
    }

    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun normalWelcomeAppearsInAllThemesAndSavesScreenshots() {
        val state = showApp(normal)
        AppTheme.entries.forEach { theme ->
            compose.runOnIdle { state.value = state.value.copy(theme = theme) }
            compose.onNodeWithText("今天想做点什么？").assertIsDisplayed()
            compose.onNodeWithText("打开一个新窗口，选好服务器和目录就能开始").assertIsDisplayed()
            compose.onNodeWithText("Codex").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Claude").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("打开工作窗口").assertDoesNotExist()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "welcome-${theme.id}.png")
                .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @Test fun welcomeButtonsPreselectTheirToolAndClaudeUsesAnOnlineLinuxServer() {
        showApp(normal)
        compose.onNodeWithText("Codex").performClick()
        compose.onNodeWithText("新建窗口").assertIsDisplayed()
        toolChoice("Codex").assertIsSelected()
        toolChoice("Claude").assertIsNotSelected()
        compose.onNodeWithContentDescription("选择服务器").assertIsDisplayed().assertTextContains("Windows fixture")
        compose.onNodeWithText("取消").performClick()

        compose.onNodeWithText("Claude").performClick()
        toolChoice("Claude").assertIsSelected().assertIsEnabled()
        toolChoice("Codex").assertIsNotSelected()
        compose.onNodeWithContentDescription("选择服务器").assertIsDisplayed().assertTextContains("Linux fixture")
        compose.onNodeWithText("打开窗口").assertIsEnabled()
        compose.onNodeWithText("取消").performClick()

        // The original toolbar and dropdown entries must continue to start with Codex.
        compose.onNodeWithContentDescription("新建窗口").performClick()
        toolChoice("Codex").assertIsSelected()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("工作窗口").performClick()
        compose.onNodeWithText("新建").performClick()
        toolChoice("Codex").assertIsSelected()
    }

    @Test fun claudeIsDisabledWhenOnlyWindowsIsOnline() {
        showApp(normal.copy(services = services.map {
            if (it.id == "linux") it.copy(runtimeState = RuntimeState.DISCONNECTED) else it
        }))
        compose.onNodeWithText("今天想做点什么？").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertIsEnabled()
        compose.onNodeWithContentDescription("Claude，需要在线的 Linux 服务器").assertIsNotEnabled()
    }

    @Test fun noServersExplainsSetupAndNavigatesToServices() {
        showApp(normal.copy(services = emptyList(), selectedServiceId = null))
        compose.onNodeWithText("先接上一台服务器吧").assertIsDisplayed()
        compose.onNodeWithText("在服务器上启动 Agent，使用和 App 相同的访问令牌").assertIsDisplayed()
        compose.onNodeWithText("去看看服务器").performClick()
        compose.runOnIdle { assertEquals(MainSection.SERVICES, model.state.value.section) }
        compose.onNodeWithText("暂无已连接服务").assertIsDisplayed()
    }

    @Test fun offlineServersExplainWaitingAndNavigateToServices() {
        showApp(normal.copy(services = services.map { it.copy(runtimeState = RuntimeState.DISCONNECTED) }))
        compose.onNodeWithText("服务器都在休息").assertIsDisplayed()
        compose.onNodeWithText("Agent 重新连上后就能开新窗口了").assertIsDisplayed()
        compose.onNodeWithText("查看服务器").performClick()
        compose.runOnIdle { assertEquals(MainSection.SERVICES, model.state.value.section) }
        compose.onNodeWithContentDescription("刷新服务").assertIsDisplayed()
    }

    @Test fun loadingWithNoServersWaitsAndThenShowsAvailableTools() {
        val state = showApp(normal.copy(services = emptyList(), loading = true))
        compose.onNodeWithText("正在连接服务器…").assertIsDisplayed()
        compose.onNodeWithText("去看看服务器").assertDoesNotExist()
        compose.onNodeWithText("Codex").assertDoesNotExist()
        compose.runOnIdle { state.value = normal }
        compose.onNodeWithText("今天想做点什么？").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertIsEnabled()
    }

    @Test fun welcomeKeepsOfflineWindowsAndLargeTextControlsReachable() {
        showApp(normal.copy(fontScale = 1.3f,
            services = services.map { if (it.id == "windows") it.copy(runtimeState = RuntimeState.DISCONNECTED) else it },
            windows = listOf(
                WorkspaceWindow("offline-one", "windows", "C:\\Users\\Fixture", "One"),
                WorkspaceWindow("offline-two", "windows", "C:\\Users\\Fixture", "Two"),
            )))
        compose.onNodeWithText("今天想做点什么？").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Codex").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Claude").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("2 个窗口在离线服务器上，连上后会回来").performScrollTo().assertIsDisplayed()
    }

    @Test fun claudePreselectionSkipsAnOfflineLinuxDefault() {
        val listed = services + services.last().copy(id = "offline-linux", name = "Offline Linux", runtimeState = RuntimeState.DISCONNECTED)
        showApp(normal.copy(services = listed, selectedServiceId = "offline-linux"))
        compose.onNodeWithText("Claude").performClick()
        toolChoice("Claude").assertIsSelected()
        compose.onNodeWithContentDescription("选择服务器").assertIsDisplayed().assertTextContains("Linux fixture")
    }

    private fun toolChoice(label: String) = compose.onNode(hasText(label) and isSelectable() and hasAnyAncestor(isDialog()))

    private fun showApp(initial: AppState): MutableState<AppState> {
        val state = mutableStateOf(initial)
        compose.setContent {
            val shellState by model.state.collectAsState()
            CodexRemoteTheme(theme = state.value.theme, fontScale = state.value.fontScale) {
                Box(Modifier.requiredWidth(360.dp).fillMaxHeight()) {
                    CodexRemoteApp(state.value.copy(section = shellState.section), model)
                }
            }
        }
        return state
    }
}
