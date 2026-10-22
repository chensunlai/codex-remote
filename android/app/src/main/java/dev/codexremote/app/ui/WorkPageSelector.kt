package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.CodingTool
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.WorkspaceWindow
import dev.codexremote.app.data.recentWorkspaceWindowIds

/** Presentation mode changes the selector, never the identity of the retained chat pane. */
@Composable
internal fun WorkPageSelector(
    state: AppState,
    window: WorkspaceWindow?,
    enabled: Boolean,
    residentWindowIds: Set<String>,
    onCreateWindow: () -> Unit,
    onSelectService: (String) -> Unit,
    onSelectWindow: (String) -> Unit,
    onCloseWindow: (String) -> Unit,
    onSwitchTool: () -> Unit,
) {
    if (state.windowMode) {
        var expanded by remember(window?.id) { mutableStateOf(false) }
        LaunchedEffect(enabled) { if (!enabled) expanded = false }
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                if (window != null) CodingToolIcon(window.toolId, Modifier.padding(end = 6.dp).size(20.dp), CodingTool.title(window.toolId))
                Text(window?.title ?: "工作窗口", Modifier.weight(1f, fill = false), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                AppIcon(CodexIcons.ChevronDown, "切换窗口", Modifier.padding(start = 6.dp).size(16.dp))
            }
            CodexDropdownMenu(expanded = enabled && expanded, onDismissRequest = { expanded = false }) {
                val byId = state.windows.associateBy { it.id }
                val onlineServiceIds = state.services.filter { it.runtimeState == RuntimeState.CONNECTED }.map { it.id }.toSet()
                val availableWindows = recentWorkspaceWindowIds(state.windows, state.recentWindowIds, window?.id)
                    .map { byId.getValue(it) }.filter { it.serviceId in onlineServiceIds }
                availableWindows.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (candidate.id == window?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { CodingToolIcon(candidate.toolId, contentDescription = CodingTool.title(candidate.toolId)) },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (candidate.id in residentWindowIds) Box(Modifier.size(7.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .semantics { contentDescription = "WebView 已保留" })
                                IconButton(onClick = { onCloseWindow(candidate.id) },
                                    enabled = candidate.id !in state.busyWindowIds) {
                                    AppIcon(CodexIcons.Close, "关闭窗口 ${candidate.title}", Modifier.size(18.dp))
                                }
                            }
                        },
                        enabled = candidate.id !in state.busyWindowIds,
                        onClick = { expanded = false; onSelectWindow(candidate.id) },
                    )
                }
                if (availableWindows.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                DropdownMenuItem(text = { Text("新建") }, leadingIcon = { AppIcon(CodexIcons.Add, null) },
                    enabled = state.services.isNotEmpty(), onClick = { expanded = false; onCreateWindow() })
            }
        }
    } else {
        val serviceId = window?.serviceId ?: state.selectedServiceId
        val service = state.services.firstOrNull { it.id == serviceId }
        val target = if (window?.toolId == CodingTool.CLAUDE.id) CodingTool.CODEX else CodingTool.CLAUDE
        val canSwitch = enabled && window != null && window.id !in state.busyWindowIds && service?.let(target::supports) == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServiceSelector(state.services, serviceId, onSelectService,
                modifier = Modifier.weight(1f), compact = true, enabled = enabled,
                showStatus = false,
                residentWebViewIds = state.windows.filter {
                    it.id in residentWindowIds && it.toolId == (window?.toolId ?: CodingTool.CODEX.id)
                }.map { it.serviceId }.toSet())
            IconButton(onClick = onSwitchTool, modifier = Modifier.width(40.dp),
                enabled = canSwitch) {
                CodingToolIcon(target.id, Modifier.size(20.dp).alpha(if (canSwitch) 1f else 0.38f), "切换到 ${target.title}")
            }
        }
    }
}
