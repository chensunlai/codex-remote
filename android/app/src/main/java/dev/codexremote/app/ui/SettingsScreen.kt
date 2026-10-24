package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.MainSection
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: AppState, viewModel: MainViewModel) {
    val link by viewModel.gatewayApi.link.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        CodexTopBar(
            title = { Text("App 设置") },
            navigationIcon = {
                IconButton(onClick = { viewModel.setSection(MainSection.SERVICES) }) {
                    AppIcon(CodexIcons.ArrowBack, contentDescription = "返回服务器")
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(Modifier.weight(1f, fill = false).widthIn(max = 620.dp)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                CodexSettingsGroup("网关连接") {
                    GatewayForm(initial = state.gatewayConfig, loading = state.connecting, error = null,
                        showBrand = false, submitLabel = "保存并测试", onSave = viewModel::configureGateway,
                        multipleEndpoints = state.multipleEndpoints)
                    link.endpoint?.let { Text("当前入口：$it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ExperimentalSettings(state.allowHttp, state.multipleEndpoints, !state.connecting, viewModel::setExperiments,
                        customWindowLimit = state.customWindowLimit, maxChatPages = state.maxChatPages,
                        customCacheLimit = state.customCacheLimit, resourceCacheBytes = state.resourceCacheBytes,
                        onCustomWindowLimitChange = viewModel::setCustomWindowLimit, onMaxChatPagesChange = viewModel::setMaxChatPages,
                        onCustomCacheLimitChange = viewModel::setCustomCacheLimit, onResourceCacheBytesChange = viewModel::setResourceCacheBytes,
                        windowMode = state.windowMode, onWindowModeChange = viewModel::setWindowMode)
                }
                CodexSettingsGroup("外观") {
                    Text("主题", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppTheme.entries.forEach { theme ->
                            val active = state.theme == theme
                            OutlinedButton(onClick = { viewModel.setTheme(theme) },
                                modifier = Modifier.weight(1f).semantics { selected = active },
                                shape = MaterialTheme.shapes.small,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
                                    contentColor = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)) {
                                Text(when (theme) {
                                    AppTheme.DARK -> "黑色"
                                    AppTheme.LIGHT -> "白色"
                                    AppTheme.ANTHROPIC -> "Anthropic"
                                }, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("字体大小", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { viewModel.updateFontScale(state.fontScale - UiPreferences.FONT_SCALE_STEP) },
                                    enabled = state.fontScale > UiPreferences.MIN_FONT_SCALE) {
                                    AppIcon(CodexIcons.Minus, "减小字体")
                                }
                                TextButton(onClick = { viewModel.updateFontScale(UiPreferences.DEFAULT_FONT_SCALE) },
                                    modifier = Modifier.semantics { contentDescription = "重置字体大小" }) {
                                    Text("${(state.fontScale * 100).roundToInt()}%")
                                }
                                IconButton(onClick = { viewModel.updateFontScale(state.fontScale + UiPreferences.FONT_SCALE_STEP) },
                                    enabled = state.fontScale < UiPreferences.MAX_FONT_SCALE) {
                                    AppIcon(CodexIcons.Add, "增大字体")
                                }
                            }
                        }
                    }
                    Text("应用于界面、对话和终端", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GatewayAdministration(state, viewModel.gatewayApi, viewModel::refreshGatewayMetadata)
                CodexSettingsGroup("应用更新") {
                    ProductUpdatePanel()
                }
                val selectedWindow = state.windows.firstOrNull { it.id == state.selectedWindowId }
                state.services.firstOrNull { it.id == state.selectedServiceId }?.takeIf { service ->
                    selectedWindow?.let { it.serviceId != service.id || it.toolId == "codex" } != false
                }?.let { service ->
                    key(state.gatewayConfig, service.id) {
                        CodexSettingsGroup("Codex CLI") { CodexManagementPanel(service, viewModel.gatewayApi) }
                    }
                }
                TextButton(onClick = viewModel::clearGateway, modifier = Modifier.align(Alignment.Start)) {
                    AppIcon(CodexIcons.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("移除此网关", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun GatewayAdministration(state: AppState, sharedApi: dev.codexremote.app.data.GatewayApi? = null, onSettingsSaved: () -> Unit) {
    val config = state.gatewayConfig
    if (config != null && state.gatewayRole == "admin" && state.gatewayAdminSupported) {
        key(config) {
            CodexSettingsGroup("Gateway 管理") {
                GatewayAdminPanel(config, state.gatewayTokenId, onSettingsSaved, sharedApi)
            }
        }
    }
}
