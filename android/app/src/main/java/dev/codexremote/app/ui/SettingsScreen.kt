@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.MainSection
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: AppState, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        CodexTopBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = { viewModel.setSection(MainSection.SERVICES) }) {
                    AppIcon(CodexIcons.ArrowBack, contentDescription = "返回服务器")
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 620.dp),
            ) {
                Text("网关", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(14.dp))
                GatewayForm(
                    initial = state.gatewayConfig,
                    loading = state.connecting,
                    error = null,
                    showBrand = false,
                    submitLabel = "保存并测试",
                    onSave = viewModel::configureGateway,
                )
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                Text("主题", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppTheme.entries.forEach { theme ->
                        val active = state.theme == theme
                        OutlinedButton(
                            onClick = { viewModel.setTheme(theme) },
                            modifier = Modifier.weight(1f).semantics { selected = active },
                            shape = MaterialTheme.shapes.small,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(when (theme) {
                                AppTheme.DARK -> "黑色"
                                AppTheme.LIGHT -> "白色"
                                AppTheme.ANTHROPIC -> "Anthropic"
                            })
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("字体大小")
                    Text("${(state.fontScale * 100).roundToInt()}%")
                }
                Slider(
                    value = state.fontScale,
                    onValueChange = viewModel::updateFontScale,
                    valueRange = UiPreferences.MIN_FONT_SCALE..UiPreferences.MAX_FONT_SCALE,
                    steps = UiPreferences.FONT_SCALE_SLIDER_STEPS,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Codex Remote", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                state.services.firstOrNull { it.id == state.selectedServiceId }?.let { service ->
                    Spacer(Modifier.height(20.dp))
                    CodexManagementPanel(service, viewModel.gatewayApi)
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = viewModel::clearGateway, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                    AppIcon(CodexIcons.Logout, contentDescription = null)
                    Text("移除此网关", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
