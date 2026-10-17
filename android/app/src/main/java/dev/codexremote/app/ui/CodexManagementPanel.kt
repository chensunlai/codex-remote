package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.objects
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun CodexManagementPanel(service: RemoteService, api: GatewayApi) {
    var status by remember(service.id) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(service.id) { mutableStateOf(false) }
    var error by remember(service.id) { mutableStateOf<String?>(null) }
    var confirmation by remember(service.id) { mutableStateOf<JSONObject?>(null) }
    var refresh by remember(service.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(service.id, refresh) {
        do {
            runCatching { api.codexRuntime(service.id) }.onSuccess { status = it; error = null }
                .onFailure { error = it.message }
            val running = status?.optJSONObject("operation")?.optString("state") == "running"
            if (running) delay(1_500)
        } while (running)
    }

    fun operate(update: Boolean, confirmed: Boolean = false) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching {
                if (update) api.updateCodex(service.id) else api.restartCodex(service.id, confirmed)
            }.onSuccess {
                if (it.optBoolean("confirmationRequired")) confirmation = it
                refresh += 1
            }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("${service.name} · Codex", style = MaterialTheme.typography.titleMedium)
        val current = status
        if (current != null) {
            Text(current.optString("executable"), style = MonoTextStyle)
            val versions = current.optJSONArray("runningVersions")?.objects()?.map {
                it.optString("version").takeIf { version -> version.isNotBlank() && version != "null" } ?: "未知"
            }?.distinct().orEmpty()
            Text("运行版本：${versions.joinToString().ifBlank { "未运行" }}")
            Text("磁盘版本：${current.versionText("installedVersion")}")
            Text("最新稳定版：${current.versionText("latestVersion")}")
            current.optJSONObject("operation")?.let { operation ->
                val label = if (operation.optString("type") == "update") "更新" else "重启"
                val state = when (operation.optString("state")) {
                    "running" -> "进行中"
                    "succeeded" -> "完成"
                    else -> "失败"
                }
                Text("$label$state")
                val output = operation.optString("error").takeUnless { it.isBlank() || it == "null" }
                    ?: operation.optString("output").takeUnless { it.isBlank() || it == "null" }
                output?.let { Text(it.takeLast(4000), style = MonoTextStyle) }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val enabled = service.runtimeState == RuntimeState.CONNECTED && !busy &&
            current?.optJSONObject("operation")?.optString("state") != "running"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current?.optBoolean("restartRequired") == true) {
                Button(onClick = { operate(false) }, enabled = enabled) { Text("重启") }
            } else {
                OutlinedButton(onClick = { operate(false) }, enabled = enabled) { Text("重启") }
            }
            val canUpdate = enabled && current?.optBoolean("updateSupported", false) == true
            if (current?.optBoolean("updateAvailable") == true) {
                Button(onClick = { operate(true) }, enabled = canUpdate) { Text("更新") }
            } else {
                OutlinedButton(onClick = { operate(true) }, enabled = canUpdate) { Text("更新") }
            }
            TextButton(onClick = { refresh += 1 }, enabled = !busy) { Text("刷新") }
        }
        if (current != null && !current.optBoolean("updateSupported", false)) {
            Text("当前 CLI 不支持 update 命令。", style = MaterialTheme.typography.bodySmall)
        }
    }

    confirmation?.let { details ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("重启 Codex？") },
            text = {
                val affected = details.optJSONArray("affected")?.length() ?: 0
                Text("该服务器有正在执行或等待交互的会话${if (affected > 0) "（$affected 个实例）" else ""}。重启会中断它们，未完成的任务不会自动重发。")
            },
            confirmButton = {
                TextButton(onClick = { confirmation = null; operate(false, true) }) { Text("立即重启") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

private fun JSONObject.versionText(name: String): String =
    optString(name).takeUnless { it.isBlank() || it == "null" } ?: "未知"
