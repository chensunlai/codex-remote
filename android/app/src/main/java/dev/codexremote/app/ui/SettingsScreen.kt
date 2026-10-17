@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = { viewModel.setSection(MainSection.SERVICES) }) {
                    Icon(CodexIcons.ArrowBack, contentDescription = "返回服务器")
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .widthIn(max = 620.dp),
            ) {
                Text("网关")
                Spacer(Modifier.height(14.dp))
                GatewayForm(
                    initial = state.gatewayConfig,
                    loading = state.connecting,
                    error = null,
                    showBrand = false,
                    submitLabel = "保存并测试",
                    onSave = viewModel::configureGateway,
                )
                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                Text("主题")
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AppTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = state.theme == theme,
                            onClick = { viewModel.setTheme(theme) },
                            shape = SegmentedButtonDefaults.itemShape(index, AppTheme.entries.size),
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
                OutlinedButton(onClick = viewModel::clearGateway, modifier = Modifier.fillMaxWidth()) {
                    Icon(CodexIcons.Logout, contentDescription = null)
                    Text("移除此网关", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
