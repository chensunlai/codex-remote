package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.objects
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

@Composable
internal fun RemoteDirectoryDialog(
    api: GatewayApi,
    serviceId: String,
    initialPath: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember(api, serviceId, initialPath) { mutableStateOf(initialPath) }
    var input by remember(api, serviceId, initialPath) { mutableStateOf(initialPath) }
    var entries by remember(api, serviceId, initialPath) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember(api, serviceId, initialPath) { mutableStateOf(true) }
    var error by remember(api, serviceId, initialPath) { mutableStateOf<String?>(null) }
    var reload by remember(api, serviceId, initialPath) { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme

    fun navigate(nextPath: String) {
        if (busy || !nextPath.startsWith('/')) return
        // Invalidate the old directory immediately, before the new request starts.
        busy = true
        entries = emptyList()
        error = null
        path = nextPath
        input = nextPath
        reload++
        focus.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(api, serviceId, path, reload) {
        busy = true
        entries = emptyList()
        error = null
        try {
            entries = api.files(serviceId, path).objects().filter { it.optString("type") == "directory" }
            busy = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "请检查连接后重试。"
            busy = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) })
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(560.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 64.dp).padding(start = 20.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(shape = MaterialTheme.shapes.extraSmall, color = colors.tertiaryContainer) {
                                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                        AppIcon(CodexIcons.FolderOpen, null, Modifier.size(20.dp), tint = colors.tertiary)
                                    }
                                }
                                Text("远端工作目录", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                IconButton(onClick = onDismiss) { AppIcon(CodexIcons.Close, "关闭目录选择") }
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text("绝对路径", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier.fillMaxWidth()
                                        .semantics { contentDescription = "绝对路径" },
                                    singleLine = true,
                                    textStyle = MonoTextStyle,
                                    shape = MaterialTheme.shapes.small,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = colors.background,
                                        unfocusedContainerColor = colors.background,
                                        unfocusedBorderColor = colors.outline,
                                        focusedBorderColor = colors.tertiary,
                                        focusedLabelColor = colors.tertiary,
                                        cursorColor = colors.tertiary,
                                    ),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { navigate(input) }),
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { navigate(input) }, enabled = !busy && input.startsWith('/'),
                                            colors = IconButtonDefaults.iconButtonColors(contentColor = colors.tertiary),
                                        ) {
                                            AppIcon(CodexIcons.ArrowBack, "打开路径", Modifier.rotate(180f))
                                        }
                                    },
                                )
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { navigate(path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }) },
                                        enabled = !busy && path != "/",
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                    ) {
                                        AppIcon(CodexIcons.ArrowUp, "返回父目录", Modifier.size(16.dp))
                                        Text("上一级", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterEnd) {
                                        Text(
                                            if (input != path) "当前：$path" else if (!busy && error == null) "${entries.size} 个文件夹" else "",
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Box(Modifier.background(colors.background).heightIn(max = 320.dp)) {
                                when {
                                    busy -> DirectoryStatus {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            CircularProgressIndicator(Modifier.size(18.dp), color = colors.tertiary, strokeWidth = 2.dp)
                                            Text("正在读取目录…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    error != null -> DirectoryStatus {
                                        Text("无法读取目录", style = MaterialTheme.typography.titleSmall)
                                        Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        TextButton(onClick = { navigate(path) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.tertiary)) {
                                            AppIcon(CodexIcons.Refresh, null)
                                            Text("重试", Modifier.padding(start = 8.dp))
                                        }
                                    }
                                    entries.isEmpty() -> DirectoryStatus {
                                        Text("没有子文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("可直接使用当前目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                                        items(entries, key = { it.getString("path") }) { entry ->
                                            Row(
                                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                                    .clickable { navigate(entry.getString("path")) }
                                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                AppIcon(CodexIcons.Folder, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                                                Text(entry.getString("name"), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                AppIcon(CodexIcons.ChevronDown, null, Modifier.size(16.dp).rotate(-90f), tint = colors.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Button(
                                onClick = { onSelect(path) },
                                enabled = !busy && error == null && input == path,
                                shape = RoundedCornerShape(8.dp),
                            ) { Text("使用此目录") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryStatus(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 128.dp).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        content = content,
    )
}
