package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.isRemoteAbsolutePath
import dev.codexremote.app.model.CodingTool
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.WorkspaceWindow
import kotlinx.coroutines.CancellationException

@Composable
internal fun WorkspaceWindows(
    state: AppState,
    viewModel: MainViewModel,
    residentIds: Set<String>,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkspaceWindow?>(null) }
    var closing by remember { mutableStateOf<WorkspaceWindow?>(null) }
    if (!creating && renaming == null && closing == null) CodexDialog(
        onDismissRequest = onDismiss,
        title = { Text("窗口 · ${state.windows.size}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (state.windows.isEmpty()) Text("新建窗口，选择服务器、工具和工作目录。")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.windows, key = { it.id }) { window ->
                    val service = state.services.firstOrNull { it.id == window.serviceId }
                    val selected = state.selectedWindowId == window.id
                    val busy = window.id in state.busyWindowIds
                    val colors = MaterialTheme.colorScheme
                    Surface(
                        onClick = { viewModel.selectWindow(window.id); onDismiss() }, enabled = !busy,
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) colors.surfaceContainerHighest else colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                    ) {
                        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (window.id in residentIds) Box(Modifier.padding(end = 8.dp).size(7.dp)
                                    .background(colors.primary, CircleShape).semantics { contentDescription = "WebView 已保留" })
                                Text(window.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { renaming = window }, enabled = !busy, modifier = Modifier.size(36.dp)) {
                                    AppIcon(CodexIcons.Edit, "重命名 ${window.title}", Modifier.size(18.dp))
                                }
                                IconButton(onClick = { closing = window }, enabled = !busy, modifier = Modifier.size(36.dp)) {
                                    AppIcon(CodexIcons.Close, "关闭 ${window.title}", Modifier.size(18.dp))
                                }
                            }
                            Text("${service?.name ?: "服务器暂不可用"} · ${CodingTool.title(window.toolId)} · ${if (busy) "正在处理" else if (service?.runtimeState == RuntimeState.CONNECTED) "在线" else "离线"}",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(window.workspace, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            }
        },
        confirmButton = { Button(onClick = { creating = true }, enabled = state.services.isNotEmpty()) {
            AppIcon(CodexIcons.Add, null, Modifier.size(18.dp)); Text("新建窗口", Modifier.padding(start = 6.dp))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
    if (creating) NewWorkspaceWindowDialog(state, viewModel,
        onDismiss = { creating = false }, onCreated = onDismiss)
    renaming?.let { window ->
        var title by remember(window.id) { mutableStateOf(window.title) }
        CodexDialog(onDismissRequest = { renaming = null }, title = { Text("重命名窗口") },
            text = { CodexTextField(title, { title = it.take(80) }, label = "窗口名称", modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { viewModel.renameWindow(window.id, title.trim()); renaming = null }, enabled = title.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
    closing?.let { window ->
        CodexDialog(onDismissRequest = { closing = null }, title = { Text("关闭 ${window.title}？") },
            text = { Text("将停止此窗口的 ${CodingTool.title(window.toolId)} 和扩展后端，正在运行的任务可能中断。关闭成功后才会移除窗口。") },
            confirmButton = { TextButton(onClick = { viewModel.closeWindow(window.id); closing = null }) { Text("关闭窗口") } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("取消") } })
    }
}

@Composable
private fun NewWorkspaceWindowDialog(state: AppState, viewModel: MainViewModel, onDismiss: () -> Unit, onCreated: () -> Unit) {
    var serviceId by remember { mutableStateOf(state.selectedServiceId ?: state.services.firstOrNull { it.runtimeState == RuntimeState.CONNECTED }?.id ?: state.services.firstOrNull()?.id) }
    val service = state.services.firstOrNull { it.id == serviceId }
    var selectingService by remember { mutableStateOf(false) }
    var selectingDirectory by remember { mutableStateOf(false) }
    var selectingTool by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(CodingTool.CODEX) }
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
                    OutlinedButton(onClick = { selectingService = true }, modifier = Modifier.fillMaxWidth()) {
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
                Box {
                    OutlinedButton(onClick = { selectingTool = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("工具", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tool.title, Modifier.weight(1f).padding(horizontal = 8.dp))
                        AppIcon(CodexIcons.ChevronDown, null, Modifier.size(16.dp))
                    }
                    CodexDropdownMenu(selectingTool, { selectingTool = false }) {
                        CodingTool.entries.forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.title + if (service?.let { candidate.supports(it) } == false) " · 仅 Linux" else "") },
                                enabled = service?.let { candidate.supports(it) } != false,
                                onClick = { tool = candidate; selectingTool = false })
                        }
                    }
                }
                if (service != null && !tool.supports(service)) Text("Claude Code 首版仅支持 Linux Agent。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                CodexTextField(title, { title = it.take(80) }, label = "窗口名称（可选）", modifier = Modifier.fillMaxWidth())
                CodexTextField(workspace, { workspace = it }, label = "工作目录", modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { selectingDirectory = true }, enabled = service?.runtimeState == RuntimeState.CONNECTED && isRemoteAbsolutePath(workspace)) {
                    AppIcon(CodexIcons.FolderOpen, null, Modifier.size(18.dp)); Text("浏览目录", Modifier.padding(start = 6.dp))
                }
                Text("首次使用时按需下载所选工具的官方插件。Claude Code 使用插件自带程序；请先在服务器配置好认证。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
