@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            TopAppBar(
                title = { Text("服务") },
                actions = {
                    IconButton(onClick = viewModel::refreshServices) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新服务")
                    }
                },
            )
        },
    ) { padding ->
        if (state.services.isEmpty()) {
            EmptyPane(
                title = "暂无已连接服务",
                icon = Icons.Outlined.Dns,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.services, key = RemoteService::id) { service ->
                    ServiceRow(
                        service = service,
                        onSelect = {
                            viewModel.selectService(service.id)
                            viewModel.setSection(MainSection.SESSIONS)
                        },
                        onTest = { viewModel.testService(service.id) },
                        onDelete = { deleteTarget = service },
                    )
                }
            }
        }
    }

    deleteTarget?.let { service ->
        AlertDialog(
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
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val connected = service.runtimeState == RuntimeState.CONNECTED
    Card(
        onClick = onSelect,
        enabled = connected,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(service.runtimeState)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(service.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(service.hostname, service.platform, service.arch)
                        .joinToString(" · ")
                        .ifBlank { service.id },
                    style = MonoTextStyle,
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
            IconButton(onClick = onTest, enabled = connected) {
                Icon(Icons.Outlined.Science, contentDescription = "测试 Codex")
            }
            IconButton(onClick = onDelete, enabled = !connected) {
                Icon(Icons.Outlined.Delete, contentDescription = "删除服务")
            }
        }
    }
}
