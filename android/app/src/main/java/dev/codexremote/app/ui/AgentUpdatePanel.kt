package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.ProductRelease
import dev.codexremote.app.data.ProductUpdates
import dev.codexremote.app.data.productUpdateAvailable
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun AgentUpdatePanel(service: RemoteService, api: GatewayApi) {
    val releases = remember { ProductUpdates() }
    DisposableEffect(releases) { onDispose { releases.close() } }
    var latest by remember(service.id) { mutableStateOf<ProductRelease?>(null) }
    var runtime by remember(service.id) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(service.id) { mutableStateOf(false) }
    var error by remember(service.id) { mutableStateOf<String?>(null) }
    var confirm by remember(service.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val supported = service.capabilities?.contains("agent-maintenance-v1") == true
    val online = service.runtimeState == RuntimeState.CONNECTED
    val operation = runtime?.optJSONObject("operation")
    val updating = operation?.optString("state") in listOf("queued", "running")
    val version = runtime?.optString("currentVersion") ?: service.agentVersion

    // Read durable status again when returning to this page; never resubmit an update.
    LaunchedEffect(service.id, supported, online, updating) {
        if (!supported || !online) return@LaunchedEffect
        do {
            try { runtime = api.agentRuntime(service.id); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = if (updating) "等待 Agent 更新后重新连接，可稍后刷新查看结果" else "无法读取更新状态，请确认 Gateway 和 Agent 均已升级：${failure.message}"
            }
            if (!updating) break
            delay(3_000)
        } while (true)
    }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agent ${version ?: "版本未知"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = !busy && !updating, onClick = {
                scope.launch {
                    busy = true; error = null
                    try {
                        latest = releases.latest()
                        if (online && supported) runtime = api.agentRuntime(service.id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "检查更新失败" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "正在检查…" else "检查更新") }
        }
        latest?.let { release ->
            Text(when (productUpdateAvailable(version, release.version)) {
                true -> "可更新至 ${release.version}"
                false -> "已是当前或更新版本"
                null -> "最新稳定版 ${release.version}，当前版本未知"
            }, style = MaterialTheme.typography.bodySmall)
            if (productUpdateAvailable(version, release.version) == true) {
                if (!online) {
                    Text("Agent 离线，连接后可更新。", style = MaterialTheme.typography.bodySmall)
                } else if (supported && runtime?.optBoolean("updateSupported") == true) {
                    Button(onClick = { confirm = true }, enabled = online && !busy && !updating, shape = MaterialTheme.shapes.small) {
                        Text(if (updating) "正在更新…" else "更新 Agent")
                    }
                } else Text(if (service.platform == "win32") "请在 Windows 托盘菜单检查更新。"
                    else "请先在服务器以原用户重新运行安装器，启用 systemd / launchd 托管。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        operation?.let {
            Text(it.optString("output"), style = MaterialTheme.typography.bodySmall,
                color = if (it.optString("state") == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (confirm) CodexDialog(
        onDismissRequest = { confirm = false },
        title = { Text("更新 ${service.name} 的 Agent") },
        text = { Text("下载和校验完成后将重启 Agent，可能中断该服务器上所有窗口的任务。配置和插件缓存会保留；启动检查失败会尝试恢复原版本。") },
        confirmButton = { TextButton(onClick = {
            confirm = false
            scope.launch {
                busy = true; error = null
                try { runtime = api.updateAgent(service.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = "更新请求未确认，请刷新查看状态后再操作：${failure.message}" }
                finally { busy = false }
            }
        }) { Text("更新并重启") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}
