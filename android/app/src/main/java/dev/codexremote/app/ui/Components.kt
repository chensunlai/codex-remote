package dev.codexremote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState

@Composable
fun AppIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(imageVector, contentDescription, modifier.size(18.dp), tint)
}

/** Compact editor-style chrome, with Android's standard 48 dp action targets. */
@Composable
fun CodexTopBar(
    title: @Composable () -> Unit,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            navigationIcon?.invoke()
            Box(Modifier.weight(1f).padding(start = if (navigationIcon == null) 16.dp else 0.dp, end = 4.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium) { title() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun ServiceSelector(
    services: List<RemoteService>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = services.firstOrNull { it.id == selectedId }
    Box(modifier) {
        androidx.compose.material3.TextButton(
            onClick = { expanded = true },
            enabled = services.isNotEmpty(),
            modifier = Modifier.widthIn(min = 160.dp, max = 280.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            shape = MaterialTheme.shapes.medium,
        ) {
            StatusDot(selected?.runtimeState ?: RuntimeState.DISCONNECTED)
            Text(
                selected?.name ?: "选择服务",
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
            )
            AppIcon(CodexIcons.ChevronDown, contentDescription = null)
        }
        CodexDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            services.forEach { service ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(service.runtimeState)
                            Text(service.name, Modifier.padding(start = 10.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(service.id)
                    },
                )
            }
        }
    }
}

@Composable
fun OptionMenu(
    label: String,
    value: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == value }?.second ?: value ?: label
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(display, maxLines = 1)
            }
            AppIcon(CodexIcons.ChevronDown, contentDescription = null)
        }
        CodexDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { expanded = false; onSelect(key) },
                )
            }
        }
    }
}

@Composable
fun EmptyPane(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = CodexIcons.CloudOff,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun StatusDot(state: RuntimeState, modifier: Modifier = Modifier) {
    val color = when (state) {
        RuntimeState.CONNECTED -> Color(0xFF2A8A65)
        RuntimeState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    androidx.compose.foundation.Canvas(modifier.then(Modifier.padding(1.dp)).widthIn(10.dp).height(10.dp)) {
        drawCircle(color)
    }
}
