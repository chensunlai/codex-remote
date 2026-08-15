@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.NewSessionOptions
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.SessionSummary
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionsScreen(state: AppState, viewModel: MainViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 920.dp
        if (split) {
            Row(Modifier.fillMaxSize()) {
                SessionListPane(
                    state,
                    viewModel,
                    onCreate = { showCreate = true },
                    onDelete = { deleteTarget = it },
                    modifier = Modifier.width(360.dp),
                )
                VerticalDivider()
                if (state.thread != null) {
                    ChatPane(state, viewModel, showBack = false, Modifier.weight(1f))
                } else {
                    EmptyPane(
                        title = "选择一个会话",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        icon = Icons.Outlined.Forum,
                    )
                }
            }
        } else if (state.thread != null) {
            ChatPane(state, viewModel, showBack = true, Modifier.fillMaxSize())
        } else {
            SessionListPane(
                state,
                viewModel,
                onCreate = { showCreate = true },
                onDelete = { deleteTarget = it },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showCreate) {
        NewSessionDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showCreate = false },
            onCreate = { options -> viewModel.createSession(options) { showCreate = false } },
        )
    }
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text(session.name ?: session.preview.ifBlank { session.id }) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSession(session.id); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionListPane(
    state: AppState,
    viewModel: MainViewModel,
    onCreate: () -> Unit,
    onDelete: (SessionSummary) -> Unit,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
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
                    IconButton(onClick = viewModel::refreshSessions, enabled = state.selectedServiceId != null) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新会话")
                    }
                    IconButton(onClick = onCreate, enabled = state.selectedServiceId != null) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建会话")
                    }
                },
            )
        },
    ) { padding ->
        if (state.selectedServiceId == null) {
            EmptyPane(title = "先连接一个服务", modifier = Modifier.padding(padding).fillMaxSize())
        } else if (state.sessions.isEmpty()) {
            EmptyPane(
                icon = Icons.Outlined.Forum,
                title = "暂无会话",
                action = { Button(onClick = onCreate) { Text("新建会话") } },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(state.sessions, key = SessionSummary::id) { session ->
                    SessionRow(
                        session,
                        selected = session.id == state.selectedThreadId,
                        onClick = { viewModel.selectSession(session.id) },
                        onArchive = { viewModel.archiveSession(session.id) },
                        onDelete = { onDelete(session) },
                    )
                    HorizontalDivider(Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            leadingContent = {
                Icon(
                    when (session.status) {
                        "active" -> Icons.Outlined.Sync
                        else -> Icons.Outlined.Forum
                    },
                    contentDescription = null,
                    tint = if (session.status == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            headlineContent = {
                Text(session.name ?: session.preview.ifBlank { "未命名会话" }, maxLines = 1)
            },
            supportingContent = {
                Column {
                    session.cwd?.let { Text(it, style = MonoTextStyle, maxLines = 1) }
                    if (session.updatedAt > 0) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(session.updatedAt * 1000)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "会话菜单")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("归档") },
                            leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                            onClick = { menu = false; onArchive() },
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun ChatPane(
    state: AppState,
    viewModel: MainViewModel,
    showBack: Boolean,
    modifier: Modifier,
) {
    val thread = state.thread ?: return
    var input by remember(thread.id) { mutableStateOf("") }
    var model by remember(thread.id, state.models) {
        mutableStateOf(state.models.firstOrNull { it.isDefault }?.id ?: state.models.firstOrNull()?.id)
    }
    val selectedModel = state.models.firstOrNull { it.id == model }
    var effort by remember(thread.id, selectedModel) {
        mutableStateOf(selectedModel?.defaultEffort ?: selectedModel?.efforts?.firstOrNull())
    }
    val listState = rememberLazyListState()
    val lastLength = thread.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(thread.messages.size, lastLength) {
        if (thread.messages.isNotEmpty()) listState.animateScrollToItem(thread.messages.lastIndex)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(thread.name ?: "会话", maxLines = 1)
                        Text(thread.cwd.orEmpty(), style = MonoTextStyle, maxLines = 1)
                    }
                },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = viewModel::closeSession) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回会话列表")
                        }
                    }
                },
                actions = {
                    Icon(
                        if (state.eventConnected) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                        contentDescription = if (state.eventConnected) "实时连接正常" else "实时连接已断开",
                        tint = if (state.eventConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                },
            )
        },
        bottomBar = {
            ChatComposer(
                input = input,
                onInput = { input = it },
                models = state.models,
                model = model,
                onModel = { selected ->
                    model = selected
                    effort = state.models.firstOrNull { it.id == selected }?.defaultEffort
                },
                effort = effort,
                onEffort = { effort = it },
                active = thread.activeTurnId != null,
                onSend = {
                    viewModel.sendMessage(input, model, effort) { input = "" }
                },
                onStop = viewModel::interruptTurn,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(thread.messages, key = ChatMessage::id) { message -> ChatMessageRow(message) }
            if (thread.activeTurnId != null && thread.messages.lastOrNull()?.role != MessageRole.ASSISTANT) {
                item(key = "active-indicator") {
                    LinearProgressIndicator(Modifier.width(72.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.role == MessageRole.USER) Arrangement.End else Arrangement.Start,
    ) {
        val color = when (message.role) {
            MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
            MessageRole.TOOL -> MaterialTheme.colorScheme.surfaceVariant
            MessageRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
            MessageRole.ASSISTANT -> Color.Transparent
        }
        Surface(
            color = color,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(if (message.role == MessageRole.USER) 0.86f else 1f),
        ) {
            SelectionContainer {
                Column(Modifier.padding(if (message.role == MessageRole.ASSISTANT) 0.dp else 12.dp)) {
                    if (message.role == MessageRole.TOOL) {
                        Text(
                            message.kind.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        message.text,
                        style = if (message.role == MessageRole.TOOL) MonoTextStyle else MaterialTheme.typography.bodyLarge,
                    )
                    message.status?.takeIf { it == "failed" || it == "declined" }?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    input: String,
    onInput: (String) -> Unit,
    models: List<ModelOption>,
    model: String?,
    onModel: (String) -> Unit,
    effort: String?,
    onEffort: (String) -> Unit,
    active: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val selected = models.firstOrNull { it.id == model }
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().imePadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionMenu(
                    label = "模型",
                    value = model,
                    options = models.map { it.id to it.displayName },
                    onSelect = onModel,
                    modifier = Modifier.weight(1f),
                )
                OptionMenu(
                    label = "推理强度",
                    value = effort,
                    options = selected?.efforts?.map { it to it }.orEmpty(),
                    onSelect = onEffort,
                    modifier = Modifier.weight(0.7f),
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    minLines = 1,
                    maxLines = 5,
                    placeholder = { Text(if (active) "继续补充" else "发送消息") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(),
                )
                FilledIconButton(
                    onClick = if (active && input.isBlank()) onStop else onSend,
                    enabled = active || input.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (active && input.isBlank()) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                        contentDescription = if (active && input.isBlank()) "停止" else "发送",
                    )
                }
            }
        }
    }
}

@Composable
private fun NewSessionDialog(
    state: AppState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onCreate: (NewSessionOptions) -> Unit,
) {
    val initialModel = state.models.firstOrNull { it.isDefault } ?: state.models.firstOrNull()
    var cwd by remember { mutableStateOf(state.remotePath) }
    var model by remember(state.models) { mutableStateOf(initialModel?.id) }
    var effort by remember(model, state.models) {
        val selected = state.models.firstOrNull { it.id == model }
        mutableStateOf(selected?.defaultEffort ?: selected?.efforts?.firstOrNull())
    }
    var approval by remember { mutableStateOf("on-request") }
    var sandbox by remember { mutableStateOf("workspace-write") }
    var network by remember { mutableStateOf(true) }
    var directoryPicker by remember { mutableStateOf(false) }
    val selectedModel = state.models.firstOrNull { it.id == model }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = cwd,
                    onValueChange = { cwd = it },
                    label = { Text("工作目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { viewModel.browse(cwd.takeIf { it.startsWith('/') }); directoryPicker = true }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "浏览目录")
                        }
                    },
                )
                OptionMenu(
                    "模型",
                    model,
                    state.models.map { it.id to it.displayName },
                    onSelect = { model = it },
                )
                OptionMenu(
                    "推理强度",
                    effort,
                    selectedModel?.efforts?.map { it to it }.orEmpty(),
                    onSelect = { effort = it },
                )
                OptionMenu(
                    "审批策略",
                    approval,
                    listOf("on-request" to "按需询问", "untrusted" to "仅不可信命令", "never" to "不询问"),
                    onSelect = { approval = it },
                )
                OptionMenu(
                    "文件权限",
                    sandbox,
                    listOf(
                        "workspace-write" to "工作区可写",
                        "read-only" to "只读",
                        "danger-full-access" to "完全访问",
                    ),
                    onSelect = { sandbox = it },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许网络", Modifier.weight(1f))
                    Switch(checked = network, onCheckedChange = { network = it })
                }
            }
        },
        confirmButton = {
            Button(
                enabled = cwd.startsWith('/') && !state.loading,
                onClick = {
                    onCreate(NewSessionOptions(cwd, model, effort, approval, sandbox, network))
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (directoryPicker) {
        DirectoryPickerDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { directoryPicker = false },
            onSelect = { cwd = it; directoryPicker = false },
        )
    }
}

@Composable
fun DirectoryPickerDialog(
    state: AppState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(20.dp).widthIn(max = 620.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
                        Text(state.remotePath, style = MonoTextStyle, maxLines = 2)
                    }
                    TextButton(onClick = { onSelect(state.remotePath) }, enabled = state.remotePath.isNotBlank()) {
                        Text("选择")
                    }
                }
                HorizontalDivider()
                if (state.remotePath != "/" && state.remotePath.isNotBlank()) {
                    ListItem(
                        headlineContent = { Text("..") },
                        leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        modifier = Modifier.clickable {
                            viewModel.browse(state.remotePath.substringBeforeLast('/').ifBlank { "/" })
                        },
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(
                        state.remoteFiles.filter { it.type == RemoteFileType.DIRECTORY },
                        key = { it.path },
                    ) { file ->
                        ListItem(
                            headlineContent = { Text(file.name) },
                            leadingContent = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                            modifier = Modifier.clickable { viewModel.browse(file.path) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}
