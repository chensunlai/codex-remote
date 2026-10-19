package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
internal fun ExperimentalSettings(http: Boolean, multiple: Boolean, enabled: Boolean = true, onChange: (Boolean, Boolean) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    TextButton(onClick = { opened = true }, enabled = enabled) { Text("实验性功能") }
    if (opened) CodexDialog(onDismissRequest = { opened = false }, title = { Text("实验性功能") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExperimentSwitch("允许 HTTP 网关", "仅在可信网络使用；HTTP 会明文传输令牌和会话。", http) { onChange(it, multiple) }
                ExperimentSwitch("多个网关入口", "同一网关按地址顺序选择入口。网络变化或重连时重新选择，稳定连接不主动抢切。", multiple) { onChange(http, it) }
            }
        }, confirmButton = { TextButton(onClick = { opened = false }) { Text("完成") } })
}

@Composable
private fun ExperimentSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange,
                modifier = Modifier.semantics { contentDescription = title })
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
