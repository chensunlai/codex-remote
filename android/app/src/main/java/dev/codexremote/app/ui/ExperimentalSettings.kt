package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexremote.app.data.UiPreferences

@Composable
internal fun ExperimentalSettings(
    http: Boolean,
    multiple: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean, Boolean) -> Unit,
    customWindowLimit: Boolean = false,
    maxChatPages: Int = UiPreferences.DEFAULT_MAX_CHAT_PAGES,
    customCacheLimit: Boolean = false,
    resourceCacheBytes: Long = UiPreferences.DEFAULT_RESOURCE_CACHE_BYTES,
    onCustomWindowLimitChange: (Boolean) -> Unit = {},
    onMaxChatPagesChange: (Int) -> Unit = {},
    onCustomCacheLimitChange: (Boolean) -> Unit = {},
    onResourceCacheBytesChange: (Long) -> Unit = {},
    windowMode: Boolean = true,
    onWindowModeChange: (Boolean) -> Unit = {},
) {
    var opened by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val activeCount = listOf(http, multiple, !windowMode, customWindowLimit, customCacheLimit).count { it }
    Surface(
        onClick = { opened = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colors.background,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(12.dp).alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.tertiaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    AppIcon(CodexIcons.Flask, null, Modifier.size(20.dp), tint = colors.tertiary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("实验性功能", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(
                    if (activeCount == 0) "连接方式、窗口与资源缓存" else "已开启 $activeCount 项 · 连接与性能调节",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            AppIcon(CodexIcons.ChevronDown, null, Modifier.size(18.dp).rotate(-90f), tint = colors.onSurfaceVariant)
        }
    }
    if (opened) CodexDialog(
        onDismissRequest = { opened = false },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(CodexIcons.Flask, null, Modifier.size(20.dp), tint = colors.tertiary)
                Text("实验性功能")
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按需开启，修改后自动保存。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                ExperimentSwitch(
                    CodexIcons.LockOpen, "允许 HTTP 网关", "连接使用 HTTP 的网关。仅用于可信网络，令牌和会话会以明文传输。", http, enabled,
                ) { onChange(it, multiple) }
                ExperimentSwitch(
                    CodexIcons.Network, "多个网关入口", "为同一网关配置内网、外网地址。网络变化或重连时按顺序选择，连接稳定时不切换。", multiple, enabled,
                ) { onChange(http, it) }
                ExperimentSwitch(
                    CodexIcons.Messages, "旧版服务器模式", "开启后按服务器选择，并通过工具图标切换 Codex／Claude；关闭后恢复窗口列表。切换时保留已有窗口、草稿与任务。", !windowMode, enabled,
                    onChange = { onWindowModeChange(!it) },
                )
                ExperimentSwitch(
                    CodexIcons.Messages, "自定义内存驻留窗口数", "切换驻留窗口无需重新加载。数量越大，内存占用越多；释放页面后远端任务仍会继续。关闭开关后保留并继续使用已选数量。",
                    customWindowLimit, enabled,
                    control = {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("驻留窗口", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, colors.outlineVariant)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onMaxChatPagesChange(maxChatPages - 1) },
                                        enabled = enabled && customWindowLimit && maxChatPages > UiPreferences.MIN_CHAT_PAGES) {
                                        AppIcon(CodexIcons.Minus, "减少驻留窗口")
                                    }
                                    Text("$maxChatPages", style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { onMaxChatPagesChange(maxChatPages + 1) },
                                        enabled = enabled && customWindowLimit && maxChatPages < UiPreferences.MAX_CHAT_PAGES) {
                                        AppIcon(CodexIcons.Add, "增加驻留窗口")
                                    }
                                }
                            }
                        }
                    },
                    onChange = onCustomWindowLimitChange,
                )
                ExperimentSwitch(
                    CodexIcons.Download, "自定义资源缓存大小", "缓存官方前端资源，减少重复下载。缩小容量会清理最近未使用的缓存；关闭开关后保留并继续使用已选容量。",
                    customCacheLimit, enabled,
                    control = {
                        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            UiPreferences.RESOURCE_CACHE_CHOICES.forEach { bytes ->
                                FilterChip(
                                    selected = bytes == resourceCacheBytes,
                                    onClick = { onResourceCacheBytesChange(bytes) },
                                    enabled = enabled && customCacheLimit,
                                    label = { Text(if (bytes >= 1024L * 1024 * 1024) "${bytes / (1024L * 1024 * 1024)} GiB" else "${bytes / (1024L * 1024)} MiB") },
                                )
                            }
                        }
                    },
                    onChange = onCustomCacheLimitChange,
                )
            }
        },
        confirmButton = {
            Button(onClick = { opened = false }, shape = MaterialTheme.shapes.extraSmall) { Text("完成") }
        },
    )
}

@Composable
private fun ExperimentSwitch(
    icon: ImageVector, title: String, description: String, checked: Boolean, enabled: Boolean,
    control: (@Composable () -> Unit)? = null,
    onChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(icon, null, Modifier.size(18.dp), tint = if (checked) colors.tertiary else colors.onSurfaceVariant)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Switch(
                    checked = checked, onCheckedChange = onChange, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = title },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = colors.tertiary,
                        checkedThumbColor = colors.background,
                        uncheckedTrackColor = colors.surfaceContainerHighest,
                        uncheckedThumbColor = colors.onSurfaceVariant,
                        uncheckedBorderColor = colors.onSurfaceVariant,
                    ),
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            control?.let { content ->
                Box(Modifier.alpha(if (checked && enabled) 1f else 0.5f)) { content() }
            }
        }
    }
}
