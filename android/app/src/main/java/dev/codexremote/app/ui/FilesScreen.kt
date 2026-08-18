@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import java.text.DateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun FilesScreen(state: AppState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var createDirectory by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RemoteFile?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.upload(context.contentResolver, it) }
    }
    val download = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val file = pendingDownload
        if (uri != null && file != null) viewModel.download(context.contentResolver, file, uri)
        pendingDownload = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ServiceSelector(
                        services = state.services,
                        selectedId = state.selectedServiceId,
                        onSelect = viewModel::selectService,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { upload.launch(arrayOf("*/*")) },
                        enabled = state.selectedServiceId != null && state.remotePath.isNotBlank(),
                    ) { Icon(CodexIcons.Upload, contentDescription = "上传文件") }
                    IconButton(
                        onClick = { createDirectory = true },
                        enabled = state.selectedServiceId != null && state.remotePath.isNotBlank(),
                    ) { Icon(CodexIcons.Add, contentDescription = "新建目录") }
                    IconButton(
                        onClick = { viewModel.browse(state.remotePath.takeIf(String::isNotBlank)) },
                        enabled = state.selectedServiceId != null,
                    ) {
                        Icon(CodexIcons.Refresh, contentDescription = "刷新目录")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.remotePath.isNotBlank()) {
                Breadcrumb(state.remotePath, viewModel::browse)
                HorizontalDivider()
            }
            when {
                state.selectedServiceId == null -> EmptyPane(title = "选择服务", modifier = Modifier.fillMaxSize())
                state.remoteFiles.isEmpty() -> EmptyPane(
                    icon = CodexIcons.FolderOpen,
                    title = "目录为空",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.remoteFiles, key = RemoteFile::path) { file ->
                        val downloadable = file.size < 0 || file.size <= state.maxDownloadBytes
                        FileRow(
                            file = file,
                            downloadable = downloadable,
                            maxDownloadBytes = state.maxDownloadBytes,
                            onOpen = {
                                if (file.type == RemoteFileType.DIRECTORY) viewModel.browse(file.path)
                                else if (downloadable) {
                                    viewModel.preview(file)
                                } else viewModel.reportDownloadLimit(file)
                            },
                            onDownload = {
                                pendingDownload = file
                                download.launch(file.name)
                            },
                            onDelete = { deleteTarget = file },
                        )
                    }
                }
            }
        }
    }

    if (createDirectory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createDirectory = false },
            title = { Text("新建目录") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("/", "") },
                    label = { Text("目录名") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.createDirectory(name) { createDirectory = false } },
                    enabled = name.isNotBlank(),
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createDirectory = false }) { Text("取消") } },
        )
    }
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除 ${file.name}") },
            text = { Text(file.path, style = MonoTextStyle) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteRemote(file); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    state.filePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::closePreview,
            title = { Text(preview.name, maxLines = 1) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(preview.content, style = MonoTextStyle)
                    }
                    if (preview.truncated) {
                        Text(
                            "预览内容已截断",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::closePreview) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split('/').filter(String::isNotBlank)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        TextButton(onClick = { onNavigate("/") }) { Text("/") }
        segments.forEachIndexed { index, segment ->
            Text("/", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { onNavigate("/" + segments.take(index + 1).joinToString("/")) }) {
                Text(segment, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    downloadable: Boolean,
    maxDownloadBytes: Long,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (file.type) {
                    RemoteFileType.DIRECTORY -> CodexIcons.Folder
                    RemoteFileType.SYMLINK -> CodexIcons.Link
                    else -> CodexIcons.File
                },
                contentDescription = null,
                tint = if (file.type == RemoteFileType.DIRECTORY) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (file.type != RemoteFileType.DIRECTORY) {
                        Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
                    }
                    if (file.modifiedAt > 0) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(file.modifiedAt * 1000)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (file.type != RemoteFileType.DIRECTORY && !downloadable) {
                    Text(
                        "超过 ${formatBytes(maxDownloadBytes)} 下载限制",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(CodexIcons.More, contentDescription = "文件菜单")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (file.type != RemoteFileType.DIRECTORY) {
                        DropdownMenuItem(
                            text = { Text("下载") },
                            leadingIcon = { Icon(CodexIcons.Download, contentDescription = null) },
                            onClick = { menu = false; onDownload() },
                            enabled = downloadable,
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除") },
                        leadingIcon = { Icon(CodexIcons.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    return "%.1f %s".format(bytes / 1024.0.pow(exponent), units[exponent - 1])
}
