@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState

@Composable
fun ServicesScreen(state: AppState, viewModel: MainViewModel) {
    var deleteTarget by remember { mutableStateOf<RemoteService?>(null) }
    Scaffold(
        topBar = {
            CodexTopBar(
                title = { Text("服务器") },
                actions = {
                    IconButton(onClick = { viewModel.setSection(MainSection.SETTINGS) }) {
                        AppIcon(CodexIcons.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = viewModel::refreshServices) {
                        AppIcon(CodexIcons.Refresh, contentDescription = "刷新服务")
                    }
                },
            )
        },
    ) { padding ->
        if (state.services.isEmpty()) {
            EmptyPane(
                title = "暂无已连接服务",
                icon = CodexIcons.Server,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.services, key = RemoteService::id) { service ->
                    ServiceRow(
                        service = service,
                        selected = service.id == state.selectedServiceId,
                        onSelect = {
                            viewModel.openServiceWindow(service.id)
                            viewModel.setSection(MainSection.SESSIONS)
                        },
                        onTest = { viewModel.testService(service.id) },
                        onDelete = { deleteTarget = service },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        }
    }

    deleteTarget?.let { service ->
        CodexDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 " + service.name) },
            text = { Text("删除 Gateway 中保存的离线服务记录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteService(service.id)
                        deleteTarget = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServiceRow(
    service: RemoteService,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val connected = service.runtimeState == RuntimeState.CONNECTED
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onSelect,
        enabled = connected,
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.background
        },
    ) {
        Row(
            Modifier.fillMaxWidth().drawBehind {
                if (selected) drawRect(accent, size = Size(2.dp.toPx(), size.height))
            }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(service.runtimeState)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(service.hostname, service.platform, service.arch)
                        .joinToString(" · ")
                        .ifBlank { service.id },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    if (connected) "在线" else service.runtimeMessage ?: "离线",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) {
                        MaterialTheme.colorScheme.primary
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            TextButton(onClick = onTest, enabled = connected) {
                AppIcon(CodexIcons.Flask, contentDescription = null)
                Text("测试", Modifier.padding(start = 4.dp))
            }
            IconButton(onClick = onDelete, enabled = !connected) {
                AppIcon(CodexIcons.Delete, contentDescription = "删除服务")
            }
        }
    }
}
