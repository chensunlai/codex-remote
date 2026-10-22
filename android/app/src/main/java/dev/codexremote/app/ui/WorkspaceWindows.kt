package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.isRemoteAbsolutePath
import dev.codexremote.app.data.defaultWorkspaceWindowTitle
import dev.codexremote.app.model.CodingTool
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.WorkspaceWindow
import kotlinx.coroutines.CancellationException

@Composable
internal fun RenameWorkspaceWindowDialog(window: WorkspaceWindow, viewModel: MainViewModel, onDismiss: () -> Unit) {
    var title by remember(window.id) { mutableStateOf(window.title) }
    CodexDialog(onDismissRequest = onDismiss, title = { Text("重命名窗口") },
        text = { CodexTextField(title, { title = it.take(80) }, label = "窗口名称", modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { viewModel.renameWindow(window.id, title.trim()); onDismiss() }, enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun CloseWorkspaceWindowDialog(window: WorkspaceWindow, viewModel: MainViewModel, onDismiss: () -> Unit) {
    CodexDialog(onDismissRequest = onDismiss, title = { Text("关闭 ${window.title}？") },
        text = { Text("将停止此窗口的 ${CodingTool.title(window.toolId)} 和扩展后端，正在运行的任务可能中断。关闭成功后才会移除窗口。") },
        confirmButton = { TextButton(onClick = { viewModel.closeWindow(window.id); onDismiss() }) { Text("关闭窗口") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun NewWorkspaceWindowDialog(state: AppState, viewModel: MainViewModel, onDismiss: () -> Unit, onCreated: () -> Unit,
    initialTool: CodingTool = CodingTool.CODEX) {
    var serviceId by remember {
        val defaultId = state.selectedServiceId ?: state.services.firstOrNull { it.runtimeState == RuntimeState.CONNECTED }?.id ?: state.services.firstOrNull()?.id
        val defaultService = state.services.firstOrNull { it.id == defaultId }
        mutableStateOf(if (initialTool == CodingTool.CLAUDE &&
            (defaultService == null || defaultService.runtimeState != RuntimeState.CONNECTED || !initialTool.supports(defaultService))) {
            state.services.firstOrNull { it.runtimeState == RuntimeState.CONNECTED && initialTool.supports(it) }?.id
        } else defaultId)
    }
    val service = state.services.firstOrNull { it.id == serviceId }
    var selectingService by remember { mutableStateOf(false) }
    var selectingDirectory by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(initialTool) }
    var workspace by remember(serviceId) { mutableStateOf(service?.home.orEmpty()) }
    var title by remember { mutableStateOf("") }
    var error by remember(serviceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(serviceId) {
        if (workspace.isBlank() && service != null && service.runtimeState == RuntimeState.CONNECTED) {
            try { workspace = viewModel.gatewayApi.home(service.id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
        }
    }
    if (!selectingDirectory) CodexDialog(onDismissRequest = onDismiss, title = { Text("新建窗口") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(onClick = { selectingService = true }, modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "选择服务器" }) {
                        AppIcon(CodexIcons.Server, null, Modifier.size(18.dp))
                        Text(service?.name ?: "选择服务器", Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AppIcon(CodexIcons.ChevronDown, null, Modifier.size(16.dp))
                    }
                    CodexDropdownMenu(selectingService, { selectingService = false }) {
                        state.services.forEach { candidate -> DropdownMenuItem(
                            text = { Text("${candidate.name}${if (candidate.runtimeState == RuntimeState.CONNECTED) "" else " · 离线"}") },
                            onClick = { serviceId = candidate.id; selectingService = false }) }
                    }
                }
                Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CodingTool.entries.forEach { candidate ->
                        val selected = tool == candidate
                        val enabled = service?.let { candidate.supports(it) } != false
                        val colors = MaterialTheme.colorScheme
                        Surface(
                            selected = selected,
                            onClick = { tool = candidate },
                            enabled = enabled,
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                            shape = MaterialTheme.shapes.small,
                            color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
                            contentColor = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                            border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                        ) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp).alpha(if (enabled) 1f else 0.38f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically) {
                                CodingToolIcon(candidate.id)
                                Text(if (candidate == CodingTool.CLAUDE) "Claude" else "Codex", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                if (service != null && !tool.supports(service)) Text("Claude 需要 Linux Agent，或支持 Claude 的新版 macOS Agent。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                CodexTextField(title, { title = it.take(80) }, label = "窗口名称", modifier = Modifier.fillMaxWidth(),
                    placeholder = service?.let { defaultWorkspaceWindowTitle(state.windows, it.id, tool.id, it.name) }.orEmpty())
                CodexTextField(workspace, { workspace = it }, label = "工作目录", modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { selectingDirectory = true }, enabled = service?.runtimeState == RuntimeState.CONNECTED && isRemoteAbsolutePath(workspace)) {
                    AppIcon(CodexIcons.FolderOpen, null, Modifier.size(18.dp)); Text("浏览目录", Modifier.padding(start = 6.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { Button(onClick = {
            serviceId?.let { viewModel.createWindow(it, workspace, title.trim(), tool.id) }
            onCreated()
        }, enabled = service?.let { it.runtimeState == RuntimeState.CONNECTED && tool.supports(it) } == true && isRemoteAbsolutePath(workspace)) { Text("打开窗口") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    if (selectingDirectory && service != null) RemoteDirectoryDialog(viewModel.gatewayApi, service.id, workspace,
        onSelect = { workspace = it; selectingDirectory = false }, onDismiss = { selectingDirectory = false })
}
