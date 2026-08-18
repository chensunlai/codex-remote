@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.NewSessionOptions
import dev.codexremote.app.model.PromptContext
import dev.codexremote.app.model.PromptContextType
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.SessionSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class SessionTarget(val id: String, val title: String)

@Composable
fun SessionsScreen(state: AppState, viewModel: MainViewModel) {
    var showCreate by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var renameTarget by remember { mutableStateOf<SessionTarget?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 920.dp
        if (split) {
            Row(Modifier.fillMaxSize()) {
                SessionListPane(
                    state = state,
                    viewModel = viewModel,
                    onCreate = { showCreate = true },
                    onRename = { renameTarget = it.target() },
                    onDelete = { deleteTarget = it.target() },
                    modifier = Modifier.width(380.dp),
                )
                VerticalDivider()
                if (state.thread != null) {
                    ChatPane(
                        state = state,
                        viewModel = viewModel,
                        showBack = false,
                        onRename = { renameTarget = SessionTarget(it.id, it.name ?: "未命名会话") },
                        onDelete = { deleteTarget = SessionTarget(it.id, it.name ?: "未命名会话") },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    EmptyPane(
                        title = "选择一个会话",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        icon = Icons.Outlined.Forum,
                    )
                }
            }
        } else if (state.thread != null) {
            ChatPane(
                state = state,
                viewModel = viewModel,
                showBack = true,
                onRename = { renameTarget = SessionTarget(it.id, it.name ?: "未命名会话") },
                onDelete = { deleteTarget = SessionTarget(it.id, it.name ?: "未命名会话") },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SessionListPane(
                state = state,
                viewModel = viewModel,
                onCreate = { showCreate = true },
                onRename = { renameTarget = it.target() },
                onDelete = { deleteTarget = it.target() },
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
    renameTarget?.let { target ->
        RenameSessionDialog(
            target = target,
            onDismiss = { renameTarget = null },
            onRename = { value -> viewModel.renameSession(target.id, value) { renameTarget = null } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text(target.title) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSession(target.id); deleteTarget = null }) {
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
    onRename: (SessionSummary) -> Unit,
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
                    FilledIconButton(
                        onClick = onCreate,
                        enabled = state.selectedServiceId != null && !state.showingArchivedSessions,
                        modifier = Modifier.padding(end = 8.dp).size(38.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建会话")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SessionFilters(state, viewModel)
            HorizontalDivider()
            when {
                state.selectedServiceId == null -> EmptyPane(
                    title = "先连接一个服务",
                    modifier = Modifier.fillMaxSize(),
                )
                state.sessions.isEmpty() -> EmptyPane(
                    icon = if (state.showingArchivedSessions) Icons.Outlined.Archive else Icons.Outlined.Forum,
                    title = if (state.sessionSearch.isNotBlank()) "没有匹配的会话" else if (state.showingArchivedSessions) "暂无归档" else "暂无会话",
                    action = if (state.showingArchivedSessions || state.sessionSearch.isNotBlank()) null else {
                        { Button(onClick = onCreate) { Text("新建会话") } }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> SessionList(
                    state = state,
                    viewModel = viewModel,
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SessionFilters(state: AppState, viewModel: MainViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.sessionSearch,
            onValueChange = viewModel::setSessionSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = if (state.sessionSearch.isNotBlank()) {
                {
                    IconButton(onClick = { viewModel.setSessionSearch("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                    }
                }
            } else null,
            placeholder = { Text("搜索会话") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !state.showingArchivedSessions,
                onClick = { viewModel.showArchivedSessions(false) },
                label = { Text("当前") },
            )
            FilterChip(
                selected = state.showingArchivedSessions,
                onClick = { viewModel.showArchivedSessions(true) },
                label = { Text("已归档") },
                leadingIcon = if (state.showingArchivedSessions) {
                    { Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(17.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun SessionList(
    state: AppState,
    viewModel: MainViewModel,
    onRename: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        itemsIndexed(state.sessions, key = { _, session -> session.id }) { index, session ->
            val group = sessionGroup(session.updatedAt)
            val previous = state.sessions.getOrNull(index - 1)?.let { sessionGroup(it.updatedAt) }
            if (group != previous) {
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
                )
            }
            SessionRow(
                session = session,
                selected = session.id == state.selectedThreadId,
                archived = state.showingArchivedSessions,
                onClick = { viewModel.selectSession(session.id) },
                onRename = { onRename(session) },
                onArchive = { viewModel.archiveSession(session.id) },
                onUnarchive = { viewModel.unarchiveSession(session.id) },
                onDelete = { onDelete(session) },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val title = session.name ?: session.preview.ifBlank { "未命名会话" }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                Icon(
                    imageVector = if (session.status == "active") Icons.Outlined.Sync else Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = if (session.status == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.name != null && session.preview.isNotBlank() && session.preview != session.name) {
                    Text(
                        session.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                session.cwd?.let {
                    Text(
                        it,
                        style = MonoTextStyle,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    sessionTime(session.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 3.dp, end = 2.dp),
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "会话菜单")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                            onClick = { menu = false; onRename() },
                        )
                        if (archived) {
                            DropdownMenuItem(
                                text = { Text("恢复") },
                                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                                onClick = { menu = false; onUnarchive() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("归档") },
                                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                                onClick = { menu = false; onArchive() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPane(
    state: AppState,
    viewModel: MainViewModel,
    showBack: Boolean,
    onRename: (dev.codexremote.app.model.ThreadDetail) -> Unit,
    onDelete: (dev.codexremote.app.model.ThreadDetail) -> Unit,
    modifier: Modifier,
) {
    val thread = state.thread ?: return
    var input by remember(thread.id) { mutableStateOf("") }
    var contexts by remember(thread.id) { mutableStateOf<List<PromptContext>>(emptyList()) }
    var model by remember(thread.id, state.models) {
        mutableStateOf(state.models.firstOrNull { it.isDefault }?.id ?: state.models.firstOrNull()?.id)
    }
    val selectedModel = state.models.firstOrNull { it.id == model }
    var effort by remember(thread.id, selectedModel?.id) {
        mutableStateOf(selectedModel?.defaultEffort ?: selectedModel?.efforts?.firstOrNull())
    }
    var menu by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val following by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    val lastLength = thread.messages.lastOrNull()?.text?.length.orZero() +
        thread.messages.lastOrNull()?.detail?.length.orZero()
    LaunchedEffect(thread.messages.size, lastLength, thread.plan, thread.latestDiff) {
        if (thread.messages.isNotEmpty() && (following || listState.layoutInfo.totalItemsCount == 0)) {
            listState.scrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            thread.name ?: "未命名会话",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        thread.cwd?.let {
                            Text(
                                it,
                                style = MonoTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
                        modifier = Modifier.size(20.dp),
                    )
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "会话操作")
                        }
                        ThreadMenu(
                            expanded = menu,
                            archived = state.showingArchivedSessions,
                            onDismiss = { menu = false },
                            onRename = { menu = false; onRename(thread) },
                            onReview = { menu = false; viewModel.reviewUncommitted() },
                            onCompact = { menu = false; viewModel.compactSession() },
                            onArchive = { menu = false; viewModel.archiveSession(thread.id) },
                            onUnarchive = { menu = false; viewModel.unarchiveSession(thread.id) },
                            onDelete = { menu = false; onDelete(thread) },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            if (!state.showingArchivedSessions) {
                PromptComposer(
                    input = input,
                    onInput = { input = it },
                    contexts = contexts,
                    onRemoveContext = { removed -> contexts = contexts.filterNot { it == removed } },
                    models = state.models,
                    model = model,
                    onModel = { selected ->
                        model = selected
                        effort = state.models.firstOrNull { it.id == selected }?.defaultEffort
                    },
                    effort = effort,
                    onEffort = { effort = it },
                    active = thread.activeTurnId != null,
                    hasMessages = thread.messages.isNotEmpty(),
                    skills = state.skills,
                    onBrowseFile = {
                        viewModel.browse(thread.cwd)
                        showFilePicker = true
                    },
                    onAddSkill = { skill ->
                        val context = PromptContext(PromptContextType.SKILL, skill.name, skill.path)
                        if (contexts.none { it.path == context.path }) contexts = contexts + context
                    },
                    onSend = {
                        viewModel.sendMessage(input.trim(), model, effort, contexts) {
                            input = ""
                            contexts = emptyList()
                        }
                    },
                    onStop = viewModel::interruptTurn,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (thread.activeFlags.isNotEmpty()) {
                item(key = "active-flags") {
                    ChatTimelineItem { itemModifier -> ActiveFlags(thread.activeFlags, itemModifier) }
                }
            }
            if (thread.plan.isNotEmpty()) {
                item(key = "current-plan") {
                    ChatTimelineItem { itemModifier ->
                        PlanPanel(thread.planExplanation, thread.plan, itemModifier)
                    }
                }
            }
            items(thread.messages, key = ChatMessage::id) { message ->
                ChatTimelineItem { itemModifier -> ChatMessageRow(message, itemModifier) }
            }
            if (thread.latestDiff.isNotBlank()) {
                item(key = "latest-diff") {
                    ChatTimelineItem { itemModifier -> UnifiedDiffPanel(thread.latestDiff, itemModifier) }
                }
            }
            if (thread.activeTurnId != null) {
                item(key = "active-indicator") {
                    ChatTimelineItem { itemModifier ->
                        Row(
                            itemModifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp)
                            Text(
                                "Codex 正在工作",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilePicker) {
        RemoteContextPickerDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showFilePicker = false },
            onSelect = { file ->
                val type = if (file.isImage()) PromptContextType.IMAGE else PromptContextType.FILE
                val context = PromptContext(type, file.name, file.path)
                if (contexts.none { it.path == context.path }) contexts = contexts + context
                showFilePicker = false
            },
        )
    }
}

@Composable
private fun ChatTimelineItem(content: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = 760.dp).fillMaxWidth())
    }
}

@Composable
private fun ThreadMenu(
    expanded: Boolean,
    archived: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onReview: () -> Unit,
    onCompact: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("重命名") },
            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            onClick = onRename,
        )
        if (!archived) {
            DropdownMenuItem(
                text = { Text("审阅未提交更改") },
                leadingIcon = { Icon(Icons.Outlined.RateReview, contentDescription = null) },
                onClick = onReview,
            )
            DropdownMenuItem(
                text = { Text("压缩上下文") },
                leadingIcon = { Icon(Icons.Outlined.Compress, contentDescription = null) },
                onClick = onCompact,
            )
            DropdownMenuItem(
                text = { Text("归档") },
                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                onClick = onArchive,
            )
        } else {
            DropdownMenuItem(
                text = { Text("恢复") },
                leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
                onClick = onUnarchive,
            )
        }
        DropdownMenuItem(
            text = { Text("删除") },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            onClick = onDelete,
        )
    }
}

@Composable
private fun ActiveFlags(flags: Set<String>, modifier: Modifier = Modifier) {
    val label = when {
        "waitingOnApproval" in flags -> "等待审批"
        "waitingOnUserInput" in flags -> "等待输入"
        else -> "会话正在运行"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PromptComposer(
    input: String,
    onInput: (String) -> Unit,
    contexts: List<PromptContext>,
    onRemoveContext: (PromptContext) -> Unit,
    models: List<ModelOption>,
    model: String?,
    onModel: (String) -> Unit,
    effort: String?,
    onEffort: (String) -> Unit,
    active: Boolean,
    hasMessages: Boolean,
    skills: List<dev.codexremote.app.model.SkillOption>,
    onBrowseFile: () -> Unit,
    onAddSkill: (dev.codexremote.app.model.SkillOption) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val selected = models.firstOrNull { it.id == model }
    var contextMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxWidth().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 784.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        if (contexts.isNotEmpty()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(start = 14.dp, top = 10.dp, end = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                contexts.forEach { context ->
                                    InputChip(
                                        selected = true,
                                        onClick = { onRemoveContext(context) },
                                        label = { Text(context.name, maxLines = 1) },
                                        leadingIcon = {
                                            Icon(
                                                when (context.type) {
                                                    PromptContextType.FILE -> Icons.Outlined.Code
                                                    PromptContextType.IMAGE -> Icons.Outlined.Image
                                                    PromptContextType.SKILL -> Icons.Outlined.Psychology
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "移除",
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = onInput,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 70.dp, max = 180.dp)
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 7,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                            keyboardActions = KeyboardActions(),
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) {
                                        Text(
                                            if (active || hasMessages) {
                                                "提出后续变更要求"
                                            } else {
                                                "描述你想完成的任务"
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 9.dp, top = 2.dp, end = 10.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                IconButton(onClick = { contextMenu = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Outlined.Add, contentDescription = "添加上下文")
                                }
                                DropdownMenu(
                                    expanded = contextMenu,
                                    onDismissRequest = { contextMenu = false },
                                    modifier = Modifier.heightIn(max = 420.dp),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("引用远端文件") },
                                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                                        onClick = { contextMenu = false; onBrowseFile() },
                                    )
                                    if (skills.isNotEmpty()) {
                                        HorizontalDivider()
                                        skills.forEach { skill ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(skill.displayName, maxLines = 1)
                                                        Text(
                                                            skill.description,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            maxLines = 2,
                                                        )
                                                    }
                                                },
                                                leadingIcon = {
                                                    Icon(Icons.Outlined.Psychology, contentDescription = null)
                                                },
                                                onClick = { contextMenu = false; onAddSkill(skill) },
                                            )
                                        }
                                    }
                                }
                            }
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = "审批保护已启用",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 7.dp).size(19.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            CompactOptionMenu(
                                value = model,
                                options = models.map { it.id to it.displayName },
                                onSelect = onModel,
                                fallback = "模型",
                                modifier = Modifier.widthIn(max = 128.dp),
                            )
                            CompactOptionMenu(
                                value = effort,
                                options = selected?.efforts?.map { it to effortLabel(it) }.orEmpty(),
                                onSelect = onEffort,
                                fallback = "推理",
                                modifier = Modifier.widthIn(max = 76.dp),
                            )
                            FilledIconButton(
                                onClick = if (active && input.isBlank()) onStop else onSend,
                                enabled = active || input.isNotBlank(),
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    if (active && input.isBlank()) {
                                        Icons.Outlined.Stop
                                    } else {
                                        Icons.Outlined.ArrowUpward
                                    },
                                    contentDescription = if (active && input.isBlank()) "停止" else "发送",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactOptionMenu(
    value: String?,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    fallback: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: value ?: fallback
    Box(modifier) {
        TextButton(
            onClick = { expanded = true },
            enabled = options.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
private fun RemoteContextPickerDialog(
    state: AppState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSelect: (RemoteFile) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f).padding(18.dp).widthIn(max = 680.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("添加上下文", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.remotePath,
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (state.remotePath != "/" && state.remotePath.isNotBlank()) {
                        item(key = "parent") {
                            ContextFileRow(
                                name = "..",
                                subtitle = "上级目录",
                                icon = Icons.Outlined.FolderOpen,
                                onClick = {
                                    viewModel.browse(state.remotePath.substringBeforeLast('/').ifBlank { "/" })
                                },
                            )
                        }
                    }
                    items(state.remoteFiles, key = RemoteFile::path) { file ->
                        ContextFileRow(
                            name = file.name,
                            subtitle = if (file.type == RemoteFileType.DIRECTORY) "目录" else formatFileSize(file.size),
                            icon = when {
                                file.type == RemoteFileType.DIRECTORY -> Icons.Outlined.FolderOpen
                                file.isImage() -> Icons.Outlined.Image
                                else -> Icons.Outlined.Code
                            },
                            onClick = {
                                if (file.type == RemoteFileType.DIRECTORY) viewModel.browse(file.path) else onSelect(file)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextFileRow(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun RenameSessionDialog(
    target: SessionTarget,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var value by remember(target.id) { mutableStateOf(target.title.takeUnless { it == "未命名会话" }.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名会话") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("名称") },
            )
        },
        confirmButton = {
            Button(onClick = { onRename(value.trim()) }, enabled = value.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                OptionMenu("模型", model, state.models.map { it.id to it.displayName }, onSelect = { model = it })
                OptionMenu("推理强度", effort, selectedModel?.efforts?.map { it to effortLabel(it) }.orEmpty(), onSelect = { effort = it })
                OptionMenu(
                    "审批策略",
                    approval,
                    listOf("on-request" to "按需询问", "untrusted" to "仅不可信命令", "never" to "不询问"),
                    onSelect = { approval = it },
                )
                OptionMenu(
                    "文件权限",
                    sandbox,
                    listOf("workspace-write" to "工作区可写", "read-only" to "只读", "danger-full-access" to "完全访问"),
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
                onClick = { onCreate(NewSessionOptions(cwd, model, effort, approval, sandbox, network)) },
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
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
                        Text(state.remotePath, style = MonoTextStyle, maxLines = 2)
                    }
                    TextButton(onClick = { onSelect(state.remotePath) }, enabled = state.remotePath.isNotBlank()) {
                        Text("选择")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (state.remotePath != "/" && state.remotePath.isNotBlank()) {
                        item(key = "parent") {
                            ContextFileRow(
                                name = "..",
                                subtitle = "上级目录",
                                icon = Icons.Outlined.FolderOpen,
                                onClick = { viewModel.browse(state.remotePath.substringBeforeLast('/').ifBlank { "/" }) },
                            )
                        }
                    }
                    items(state.remoteFiles.filter { it.type == RemoteFileType.DIRECTORY }, key = RemoteFile::path) { file ->
                        ContextFileRow(file.name, "目录", Icons.Outlined.FolderOpen) { viewModel.browse(file.path) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

private fun SessionSummary.target(): SessionTarget =
    SessionTarget(id, name ?: preview.ifBlank { "未命名会话" })

private fun sessionGroup(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "更早"
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.getDefault()))
    }
}

private fun sessionTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    val value = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    return if (value.toLocalDate() == LocalDate.now()) {
        value.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        value.format(DateTimeFormatter.ofPattern("M/d"))
    }
}

private fun RemoteFile.isImage(): Boolean =
    name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")

private fun Int?.orZero(): Int = this ?: 0

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
}

private fun effortLabel(value: String): String = when (value) {
    "minimal" -> "极低"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    else -> value
}
