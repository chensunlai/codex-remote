package dev.codexremote.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.codexremote.app.model.GatewayConfig
import org.junit.Rule
import org.junit.Test

class GatewayFormInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun experimentalEntrancesRequireOptInWithoutClearingTheAddressDraft() {
        val http = mutableStateOf(false)
        val multiple = mutableStateOf(false)
        compose.setContent {
            CodexRemoteTheme {
                Column {
                    GatewayForm(null, false, null, false, "连接网关", { _, _, _ -> }, multipleEndpoints = multiple.value)
                    ExperimentalSettings(http.value, multiple.value) { h, m -> http.value = h; multiple.value = m }
                }
            }
        }
        compose.onNodeWithContentDescription("网关地址").performTextInput("https://example.com")
        compose.onNodeWithContentDescription("优先入口（每行一个，按顺序）").assertDoesNotExist()
        compose.onNodeWithContentDescription("允许 HTTP 网关").assertDoesNotExist()
        compose.onNodeWithText("实验性功能").performClick()
        compose.onNodeWithContentDescription("允许 HTTP 网关").assertIsOff().performClick()
        compose.onNodeWithContentDescription("多个网关入口").assertIsOff().performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("网关地址").assertTextContains("https://example.com")
        compose.onNodeWithContentDescription("优先入口（每行一个，按顺序）")
            .performTextInput("http://192.168.1.1:6767\nhttps://example.com")
        compose.onNodeWithText("实验性功能").performClick()
        compose.onNodeWithContentDescription("多个网关入口").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("优先入口（每行一个，按顺序）").assertDoesNotExist()
    }

    @Test
    fun connectionProgressStaysSquareAndLocksOnlyThePendingForm() {
        val loading = mutableStateOf(true)
        compose.setContent {
            CodexRemoteTheme {
                GatewayForm(
                    initial = GatewayConfig("https://fixture.invalid", "fixture-token"),
                    loading = loading.value,
                    error = null,
                    showBrand = false,
                    submitLabel = "保存并测试",
                    onSave = { _, _, _ -> },
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            useUnmergedTree = true,
        ).assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        compose.onNodeWithContentDescription("网关地址").assertIsNotEnabled()
        compose.onNodeWithContentDescription("访问令牌").assertIsNotEnabled()
        compose.onNodeWithContentDescription("显示令牌").assertIsNotEnabled()
        compose.onNodeWithText("正在连接").assertIsNotEnabled()

        compose.runOnIdle { loading.value = false }
        compose.onNodeWithContentDescription("网关地址").assertIsEnabled()
        compose.onNodeWithContentDescription("访问令牌").assertIsEnabled()
        compose.onNodeWithText("保存并测试").assertIsEnabled()
    }
}
