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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.MainSection

@Composable
fun SettingsScreen(state: AppState, viewModel: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("设置") },
            navigationIcon = {
                IconButton(onClick = { viewModel.setSection(MainSection.SERVICES) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回服务器")
                }
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .widthIn(max = 620.dp),
            ) {
                Text("网关")
                Spacer(Modifier.height(14.dp))
                GatewayForm(
                    initial = state.gatewayConfig,
                    loading = state.loading,
                    error = null,
                    showBrand = false,
                    submitLabel = "保存并测试",
                    onSave = viewModel::configureGateway,
                )
                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                OutlinedButton(onClick = viewModel::clearGateway, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                    Text("移除此网关", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
