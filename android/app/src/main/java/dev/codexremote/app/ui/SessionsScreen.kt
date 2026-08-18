@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AccountRateLimits
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.CollaborationModeOption
import dev.codexremote.app.model.CollaborationModeSelection
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.PermissionProfile
import dev.codexremote.app.model.PromptContext
import dev.codexremote.app.model.PromptContextType
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileMatch
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.SessionSummary
import dev.codexremote.app.model.SkillOption
import dev.codexremote.app.model.ThreadDetail
import dev.codexremote.app.model.ThreadGoal
import dev.codexremote.app.model.ThreadSettingsUpdate
import dev.codexremote.app.model.ThreadTokenUsage
import dev.codexremote.app.model.TurnSummary
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private data class SessionTarget(val id: String, val title: String)

private sealed interface ChatTimelineEntry {
    val key: String

    data class Message(val message: ChatMessage) : ChatTimelineEntry {
        override val key: String = "message:${message.id}"
    }

    data class Activity(
        val turnId: String?,
        val messages: List<ChatMessage>,
        val turn: TurnSummary?,
        override val key: String,
    ) : ChatTimelineEntry
}

@Composable
fun SessionsScreen(state: AppState, viewModel: MainViewModel) {
    var deleteTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var renameTarget by remember { mutableStateOf<SessionTarget?>(null) }
    var showDirectoryPicker by remember { mutableStateOf(false) }
    val requestNewSession = {
        viewModel.browseHome()
        showDirectoryPicker = true
    }

    BackHandler(enabled = state.thread != null) { viewModel.closeSession() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 920.dp
        if (split) {
            Row(Modifier.fillMaxSize()) {
                SessionListPane(
                    state = state,
                    viewModel = viewModel,
                    onCreate = requestNewSession,
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
                        onNew = requestNewSession,
                        onRename = { renameTarget = SessionTarget(it.id, it.displayTitle(state.sessions)) },
                        onDelete = { deleteTarget = SessionTarget(it.id, it.displayTitle(state.sessions)) },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    EmptyPane(
                        title = "选择一个会话",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        icon = CodexIcons.Messages,
                    )
                }
            }
        } else if (state.thread != null) {
            ChatPane(
                state = state,
                viewModel = viewModel,
                showBack = true,
                onNew = requestNewSession,
                onRename = { renameTarget = SessionTarget(it.id, it.displayTitle(state.sessions)) },
                onDelete = { deleteTarget = SessionTarget(it.id, it.displayTitle(state.sessions)) },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            SessionListPane(
                state = state,
                viewModel = viewModel,
                onCreate = requestNewSession,
                onRename = { renameTarget = it.target() },
                onDelete = { deleteTarget = it.target() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (showDirectoryPicker) {
        RemoteDirectoryPickerDialog(
            state = state,
            viewModel = viewModel,
            onDismiss = { showDirectoryPicker = false },
            onSelect = { cwd ->
                showDirectoryPicker = false
                viewModel.createSession(cwd)
            },
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
                        Icon(CodexIcons.Refresh, contentDescription = "刷新会话")
                    }
                    FilledIconButton(
                        onClick = onCreate,
                        enabled = state.selectedServiceId != null && !state.showingArchivedSessions,
                        modifier = Modifier.padding(end = 8.dp).size(38.dp),
                    ) {
                        Icon(CodexIcons.Add, contentDescription = "新建会话")
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
                    icon = if (state.showingArchivedSessions) CodexIcons.Archive else CodexIcons.Messages,
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
            leadingIcon = { Icon(CodexIcons.Search, contentDescription = null) },
            trailingIcon = if (state.sessionSearch.isNotBlank()) {
                {
                    IconButton(onClick = { viewModel.setSessionSearch("") }) {
                        Icon(CodexIcons.Close, contentDescription = "清除搜索")
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
                    { Icon(CodexIcons.Archive, contentDescription = null, modifier = Modifier.size(17.dp)) }
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                Icon(
                    imageVector = if (session.status == "active") CodexIcons.Loading else CodexIcons.Message,
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
                        Icon(CodexIcons.More, contentDescription = "会话菜单")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(CodexIcons.PencilLine, contentDescription = null) },
                            onClick = { menu = false; onRename() },
                        )
                        if (archived) {
                            DropdownMenuItem(
                                text = { Text("恢复") },
                                leadingIcon = { Icon(CodexIcons.ArchiveRestore, contentDescription = null) },
                                onClick = { menu = false; onUnarchive() },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("归档") },
                                leadingIcon = { Icon(CodexIcons.Archive, contentDescription = null) },
                                onClick = { menu = false; onArchive() },
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
}

@Composable
private fun ChatPane(
    state: AppState,
    viewModel: MainViewModel,
    showBack: Boolean,
    onNew: () -> Unit,
    onRename: (dev.codexremote.app.model.ThreadDetail) -> Unit,
    onDelete: (dev.codexremote.app.model.ThreadDetail) -> Unit,
    modifier: Modifier,
) {
    val thread = state.thread ?: return
    val threadTitle = thread.displayTitle(state.sessions)
    var input by remember(thread.id) { mutableStateOf("") }
    var contexts by remember(thread.id) { mutableStateOf<List<PromptContext>>(emptyList()) }
    var showGoalEditor by remember(thread.id) { mutableStateOf(false) }
    var goalDraft by remember(thread.id) { mutableStateOf("") }
    val activityExpansion = remember(thread.id) { mutableStateMapOf<String, Boolean>() }
    val model = thread.settings.model
        ?: state.models.firstOrNull { it.isDefault }?.id
        ?: state.models.firstOrNull()?.id
    val selectedModel = state.models.firstOrNull { it.id == model }
    val effort = thread.settings.effort
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
    val timeline = remember(thread.messages, thread.turns) {
        buildChatTimeline(thread.messages, thread.turns)
    }
    val showThinking = remember(
        thread.activeTurnId,
        thread.activeFlags,
        thread.messages,
    ) {
        shouldShowThinking(thread)
    }
    val hazeState = rememberHazeState()
    val glassBackground = MaterialTheme.colorScheme.background
    val glassTint = MaterialTheme.colorScheme.surfaceContainerHigh
    val glassStyle = remember(glassBackground, glassTint) {
        val dark = glassBackground.luminance() < 0.5f
        HazeStyle(
            backgroundColor = glassBackground,
            tint = HazeTint(glassTint.copy(alpha = if (dark) 0.78f else 0.7f)),
            blurRadius = 24.dp,
            noiseFactor = 0.025f,
            fallbackTint = HazeTint(glassTint.copy(alpha = if (dark) 0.96f else 0.94f)),
        )
    }
    LaunchedEffect(thread.messages.size, lastLength, thread.plan, thread.latestDiff) {
        if (thread.messages.isNotEmpty() && (following || listState.layoutInfo.totalItemsCount == 0)) {
            listState.scrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
        }
    }

    Scaffold(
        modifier = modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            threadTitle,
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
                            Icon(CodexIcons.ArrowBack, contentDescription = "返回会话列表")
                        }
                    }
                },
                actions = {
                    Icon(
                        if (state.eventConnected) CodexIcons.Wifi else CodexIcons.WifiOff,
                        contentDescription = if (state.eventConnected) "实时连接正常" else "实时连接已断开",
                        tint = if (state.eventConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(CodexIcons.More, contentDescription = "会话操作")
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
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        glassBackground.copy(alpha = 0.72f),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .hazeEffect(state = hazeState, style = glassStyle),
                    ) {
                        if (thread.goal != null || showGoalEditor) {
                            GoalPanel(
                            goal = thread.goal,
                            editing = showGoalEditor,
                            draft = goalDraft,
                            onDraft = { goalDraft = it },
                            onEdit = {
                                goalDraft = thread.goal?.objective.orEmpty()
                                showGoalEditor = true
                            },
                            onSave = {
                                viewModel.setGoal(goalDraft) {
                                    showGoalEditor = false
                                    goalDraft = ""
                                }
                            },
                            onCancel = {
                                showGoalEditor = false
                                goalDraft = ""
                            },
                            onPause = { viewModel.setGoalStatus("paused") },
                            onResume = { viewModel.setGoalStatus("active") },
                            onClear = viewModel::clearGoal,
                            )
                        }
                        PromptComposer(
                        input = input,
                        onInput = { input = it },
                        contexts = contexts,
                        onRemoveContext = { removed -> contexts = contexts.filterNot { it == removed } },
                        models = state.models,
                        model = model,
                        onModel = { nextModel ->
                            val option = state.models.firstOrNull { it.id == nextModel }
                            val nextEffort = effort?.takeIf { it in option?.efforts.orEmpty() }
                                ?: option?.defaultEffort
                                ?: option?.efforts?.firstOrNull()
                            viewModel.updateThreadSettings(
                                ThreadSettingsUpdate(model = nextModel, effort = nextEffort),
                            )
                        },
                        effort = effort,
                        onEffort = { nextEffort ->
                            viewModel.updateThreadSettings(ThreadSettingsUpdate(effort = nextEffort))
                        },
                        permissionProfiles = state.permissionProfiles,
                        permissionProfile = thread.settings.permissionProfile,
                        onPermissionProfile = { profile ->
                            viewModel.updateThreadSettings(
                                ThreadSettingsUpdate(permissionProfile = profile),
                            )
                        },
                        active = thread.activeTurnId != null,
                        hasMessages = thread.messages.isNotEmpty(),
                        tokenUsage = thread.tokenUsage,
                        threadId = thread.id,
                        rateLimits = state.rateLimits,
                        skills = state.skills,
                        collaborationModes = state.collaborationModes,
                        collaborationMode = thread.settings.collaborationMode,
                        onToggleCollaborationMode = {
                            val nextMode = if (thread.settings.collaborationMode == "plan") {
                                "default"
                            } else {
                                "plan"
                            }
                            val option = state.collaborationModes.firstOrNull { it.mode == nextMode }
                            val nextModel = option?.model ?: model
                            if (option != null && nextModel != null) {
                                viewModel.updateThreadSettings(
                                    ThreadSettingsUpdate(
                                        collaborationMode = CollaborationModeSelection(
                                            mode = option.mode,
                                            model = nextModel,
                                            effort = option.effort ?: effort,
                                        ),
                                    ),
                                )
                            }
                        },
                        fileSearchQuery = state.contextFileSearchQuery,
                        fileSearchResults = state.contextFileSearchResults,
                        onFileSearchQuery = viewModel::searchContextFiles,
                        onClearFileSearch = viewModel::clearContextFileSearch,
                        onBrowseFile = {
                            viewModel.clearContextFileSearch()
                            viewModel.browse(thread.cwd)
                            showFilePicker = true
                        },
                        onFileSearchResult = { file ->
                            viewModel.clearContextFileSearch()
                            val type = if (
                                file.type == RemoteFileType.FILE && file.path.isImagePath()
                            ) {
                                PromptContextType.IMAGE
                            } else {
                                PromptContextType.FILE
                            }
                            val context = PromptContext(type, file.name, file.path)
                            if (contexts.none { it.path == context.path }) contexts = contexts + context
                        },
                        onAddSkill = { skill ->
                            val context = PromptContext(PromptContextType.SKILL, skill.name, skill.path)
                            if (contexts.none { it.path == context.path }) contexts = contexts + context
                        },
                        onGoal = {
                            goalDraft = thread.goal?.objective.orEmpty()
                            showGoalEditor = true
                        },
                        onNew = onNew,
                        onCompact = viewModel::compactSession,
                        onReview = viewModel::reviewUncommitted,
                        onSend = {
                            val submitted = input.trim()
                            when {
                                submitted == "/goal" -> {
                                    goalDraft = thread.goal?.objective.orEmpty()
                                    showGoalEditor = true
                                    input = ""
                                    contexts = emptyList()
                                }
                                submitted.startsWith("/goal ") -> {
                                    viewModel.setGoal(submitted.removePrefix("/goal ").trim()) {
                                        input = ""
                                        contexts = emptyList()
                                    }
                                }
                                submitted == "/compact" -> {
                                    viewModel.compactSession()
                                    input = ""
                                    contexts = emptyList()
                                }
                                submitted == "/review-mode" -> {
                                    viewModel.reviewUncommitted()
                                    input = ""
                                    contexts = emptyList()
                                }
                                submitted == "/new" -> {
                                    onNew()
                                    input = ""
                                    contexts = emptyList()
                                }
                                else -> viewModel.sendMessage(submitted, model, effort, contexts) {
                                    input = ""
                                    contexts = emptyList()
                                }
                            }
                        },
                        onStop = viewModel::interruptTurn,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .hazeSource(state = hazeState),
            contentPadding = PaddingValues(
                start = 14.dp,
                top = 12.dp,
                end = 14.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (thread.plan.isNotEmpty()) {
                item(key = "current-plan") {
                    ChatTimelineItem { itemModifier ->
                        PlanPanel(thread.planExplanation, thread.plan, itemModifier)
                    }
                }
            }
            items(timeline, key = ChatTimelineEntry::key) { entry ->
                ChatTimelineItem { itemModifier ->
                    when (entry) {
                        is ChatTimelineEntry.Message -> ChatMessageRow(entry.message, itemModifier)
                        is ChatTimelineEntry.Activity -> ActivityGroup(
                            messages = entry.messages,
                            turn = entry.turn,
                            isActiveTurn = entry.turnId == thread.activeTurnId,
                            expanded = activityExpansion[entry.key]
                                ?: (entry.turnId == thread.activeTurnId),
                            onExpandedChange = { activityExpansion[entry.key] = it },
                            itemExpanded = { message ->
                                activityExpansion["item:${message.id}"]
                                    ?: message.status.isRunningStatus()
                            },
                            onItemExpandedChange = { message, expanded ->
                                activityExpansion["item:${message.id}"] = expanded
                            },
                            modifier = itemModifier,
                        )
                    }
                }
            }
            if (thread.latestDiff.isNotBlank()) {
                item(key = "latest-diff") {
                    ChatTimelineItem { itemModifier -> UnifiedDiffPanel(thread.latestDiff, itemModifier) }
                }
            }
            if (thread.activeFlags.isNotEmpty()) {
                item(key = "active-flags") {
                    ChatTimelineItem { itemModifier -> ActiveFlags(thread.activeFlags, itemModifier) }
                }
            }
            if (showThinking) {
                item(key = "thinking-indicator") {
                    ChatTimelineItem { itemModifier -> ThinkingIndicator(itemModifier) }
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

private fun buildChatTimeline(
    messages: List<ChatMessage>,
    turns: List<TurnSummary>,
): List<ChatTimelineEntry> {
    val turnsById = turns.associateBy(TurnSummary::id)
    val result = mutableListOf<ChatTimelineEntry>()
    val activity = mutableListOf<ChatMessage>()
    var activityTurnId: String? = null
    var activityIndex = 0

    fun flushActivity() {
        if (activity.isEmpty()) return
        val firstId = activity.first().id
        result += ChatTimelineEntry.Activity(
            turnId = activityTurnId,
            messages = activity.toList(),
            turn = activityTurnId?.let(turnsById::get),
            key = "activity:${activityTurnId.orEmpty()}:$activityIndex:$firstId",
        )
        activity.clear()
        activityTurnId = null
        activityIndex += 1
    }

    messages.forEach { message ->
        val isFinalAssistant = message.role == MessageRole.ASSISTANT && message.phase != "commentary"
        val isStandalone = message.role == MessageRole.USER || isFinalAssistant
        if (isStandalone) {
            flushActivity()
            result += ChatTimelineEntry.Message(message)
        } else {
            if (activity.isNotEmpty() && activityTurnId != message.turnId) flushActivity()
            activityTurnId = message.turnId
            activity += message
        }
    }
    flushActivity()
    return result
}

private fun shouldShowThinking(thread: ThreadDetail): Boolean {
    val activeTurnId = thread.activeTurnId ?: return false
    if (thread.activeFlags.any { it == "waitingOnApproval" || it == "waitingOnUserInput" }) {
        return false
    }
    val activeMessages = thread.messages.filter { it.turnId == activeTurnId }
    val activeActivity = activeMessages.any { message ->
        message.phase == "commentary" && message.status.isRunningStatus() ||
            message.role in setOf(MessageRole.SYSTEM, MessageRole.TOOL) && message.status.isRunningStatus()
    }
    val assistantStreaming = activeMessages.any { message ->
        message.role == MessageRole.ASSISTANT &&
            message.phase != "commentary" &&
            message.status.isRunningStatus()
    }
    return !activeActivity && !assistantStreaming
}

private fun String?.isRunningStatus(): Boolean = this in setOf("inProgress", "in_progress", "active")

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
            leadingIcon = { Icon(CodexIcons.PencilLine, contentDescription = null) },
            onClick = onRename,
        )
        if (!archived) {
            DropdownMenuItem(
                text = { Text("审阅未提交更改") },
                leadingIcon = { Icon(CodexIcons.Review, contentDescription = null) },
                onClick = onReview,
            )
            DropdownMenuItem(
                text = { Text("压缩上下文") },
                leadingIcon = { Icon(CodexIcons.Compact, contentDescription = null) },
                onClick = onCompact,
            )
            DropdownMenuItem(
                text = { Text("归档") },
                leadingIcon = { Icon(CodexIcons.Archive, contentDescription = null) },
                onClick = onArchive,
            )
        } else {
            DropdownMenuItem(
                text = { Text("恢复") },
                leadingIcon = { Icon(CodexIcons.ArchiveRestore, contentDescription = null) },
                onClick = onUnarchive,
            )
        }
        DropdownMenuItem(
            text = { Text("删除") },
            leadingIcon = { Icon(CodexIcons.Delete, contentDescription = null) },
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
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                CodexIcons.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun GoalPanel(
    goal: ThreadGoal?,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClear: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }
    Surface(color = Color.Transparent) {
        Box(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                if (editing) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, top = 7.dp, end = 5.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            CodexIcons.Flag,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        BasicTextField(
                            value = draft,
                            onValueChange = onDraft,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 88.dp)
                                .focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (draft.isBlank()) {
                                        Text(
                                            "Goal",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                            Icon(CodexIcons.Close, contentDescription = "取消编辑 Goal")
                        }
                        IconButton(
                            onClick = onSave,
                            enabled = draft.isNotBlank(),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(CodexIcons.Check, contentDescription = "保存 Goal")
                        }
                    }
                } else if (goal != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, top = 7.dp, end = 5.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(
                            CodexIcons.Flag,
                            contentDescription = null,
                            tint = if (goal.status == "active") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                goal.objective,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                goalStatusLabel(goal),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                            Icon(CodexIcons.Edit, contentDescription = "编辑 Goal")
                        }
                        when (goal.status) {
                            "active" -> IconButton(onClick = onPause, modifier = Modifier.size(34.dp)) {
                                Icon(CodexIcons.Pause, contentDescription = "暂停 Goal")
                            }
                            "paused", "blocked", "usageLimited", "budgetLimited" -> IconButton(
                                onClick = onResume,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(CodexIcons.Play, contentDescription = "继续 Goal")
                            }
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(34.dp)) {
                            Icon(CodexIcons.Close, contentDescription = "清除 Goal")
                        }
                    }
                }
            }
        }
    }
}

private fun goalStatusLabel(goal: ThreadGoal): String {
    val status = when (goal.status) {
        "active" -> "进行中"
        "paused" -> "已暂停"
        "blocked" -> "已阻塞"
        "usageLimited" -> "用量受限"
        "budgetLimited" -> "预算已用尽"
        "complete" -> "已完成"
        else -> goal.status
    }
    val budget = goal.tokenBudget?.let { " · ${formatTokenCount(goal.tokensUsed)} / ${formatTokenCount(it)} tokens" }
        .orEmpty()
    return status + budget
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
    permissionProfiles: List<PermissionProfile>,
    permissionProfile: String?,
    onPermissionProfile: (String) -> Unit,
    active: Boolean,
    hasMessages: Boolean,
    tokenUsage: ThreadTokenUsage?,
    threadId: String,
    rateLimits: AccountRateLimits?,
    skills: List<SkillOption>,
    collaborationModes: List<CollaborationModeOption>,
    collaborationMode: String,
    onToggleCollaborationMode: () -> Unit,
    fileSearchQuery: String,
    fileSearchResults: List<RemoteFileMatch>,
    onFileSearchQuery: (String) -> Unit,
    onClearFileSearch: () -> Unit,
    onBrowseFile: () -> Unit,
    onFileSearchResult: (RemoteFileMatch) -> Unit,
    onAddSkill: (SkillOption) -> Unit,
    onGoal: () -> Unit,
    onNew: () -> Unit,
    onCompact: () -> Unit,
    onReview: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val selected = models.firstOrNull { it.id == model }
    var panel by remember { mutableStateOf<ComposerPanel?>(null) }
    var slashSession by remember { mutableStateOf(false) }
    val slashInput = input.trimStart().takeIf { value ->
        value.startsWith('/') && value.none(Char::isWhitespace)
    }
    val slashQuery = slashInput?.removePrefix("/").orEmpty()
    val planAvailable = remember(collaborationModes, model) {
        model != null &&
            collaborationModes.any { it.mode == "plan" } &&
            collaborationModes.any { it.mode == "default" }
    }
    val planEnabled = collaborationMode == "plan"

    LaunchedEffect(slashInput) {
        when {
            slashInput != null && !slashSession -> {
                slashSession = true
                panel = ComposerPanel.COMMANDS
            }
            slashInput == null && slashSession -> {
                slashSession = false
                panel = null
            }
        }
    }

    fun finishPalette() {
        val clearSlash = slashSession
        slashSession = false
        panel = null
        onClearFileSearch()
        if (clearSlash) onInput("")
    }

    fun openToolbarPanel(next: ComposerPanel) {
        if (slashSession) onInput("")
        slashSession = false
        panel = next
    }

    Surface(color = Color.Transparent) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = 784.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                panel?.let { currentPanel ->
                    ComposerPalette(
                        panel = currentPanel,
                        slashQuery = slashQuery,
                        fileQuery = fileSearchQuery,
                        fileResults = fileSearchResults,
                        models = models,
                        selectedModel = model,
                        selectedEffort = effort,
                        permissionProfiles = permissionProfiles,
                        selectedPermission = permissionProfile,
                        skills = skills,
                        threadId = threadId,
                        tokenUsage = tokenUsage,
                        rateLimits = rateLimits,
                        planAvailable = planAvailable,
                        planEnabled = planEnabled,
                        turnActive = active,
                        onBack = {
                            panel = if (slashSession) ComposerPanel.COMMANDS else null
                        },
                        onFileQuery = onFileSearchQuery,
                        onBrowseFiles = {
                            finishPalette()
                            onBrowseFile()
                        },
                        onFile = { file ->
                            finishPalette()
                            onFileSearchResult(file)
                        },
                        onGoal = {
                            finishPalette()
                            onGoal()
                        },
                        onTogglePlan = {
                            finishPalette()
                            onToggleCollaborationMode()
                        },
                        onModel = { value ->
                            finishPalette()
                            onModel(value)
                        },
                        onEffort = { value ->
                            finishPalette()
                            onEffort(value)
                        },
                        onPermission = { value ->
                            finishPalette()
                            onPermissionProfile(value)
                        },
                        onSkill = { skill ->
                            finishPalette()
                            onAddSkill(skill)
                        },
                        onNew = {
                            finishPalette()
                            onNew()
                        },
                        onCompact = {
                            finishPalette()
                            onCompact()
                        },
                        onReview = {
                            finishPalette()
                            onReview()
                        },
                        onOpenPanel = { panel = it },
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
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
                                                    PromptContextType.FILE -> CodexIcons.Code
                                                    PromptContextType.IMAGE -> CodexIcons.Image
                                                    PromptContextType.SKILL -> CodexIcons.Brain
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        },
                                        trailingIcon = {
                                            Icon(
                                                CodexIcons.Close,
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
                            IconButton(
                                onClick = {
                                    if (panel == ComposerPanel.ADD) {
                                        panel = null
                                        onClearFileSearch()
                                    } else {
                                        if (slashSession) onInput("")
                                        slashSession = false
                                        panel = ComposerPanel.ADD
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    CodexIcons.Add,
                                    contentDescription = "添加",
                                    tint = if (panel == ComposerPanel.ADD) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            IconButton(
                                onClick = { openToolbarPanel(ComposerPanel.PERMISSIONS) },
                                enabled = permissionProfiles.isNotEmpty(),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    CodexIcons.Shield,
                                    contentDescription = permissionProfile
                                        ?.let { "权限：${permissionProfileLabel(it)}" }
                                        ?: "选择权限",
                                    tint = if (permissionProfile == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.tertiary
                                    },
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            tokenUsage?.let { ContextUsageButton(it) }
                            TextButton(
                                onClick = { openToolbarPanel(ComposerPanel.MODELS) },
                                enabled = models.isNotEmpty(),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.widthIn(max = 126.dp),
                            ) {
                                Text(
                                    selected?.displayName ?: model ?: "model",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { openToolbarPanel(ComposerPanel.EFFORTS) },
                                enabled = selected?.efforts?.isNotEmpty() == true,
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.widthIn(max = 68.dp),
                            ) {
                                Text(
                                    effort ?: "effort",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            FilledIconButton(
                                onClick = if (active && input.isBlank()) onStop else onSend,
                                enabled = active || input.isNotBlank(),
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    if (active && input.isBlank()) {
                                        CodexIcons.Stop
                                    } else {
                                        CodexIcons.ArrowUp
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
private fun ContextUsageButton(usage: ThreadTokenUsage) {
    val contextWindow = usage.contextWindow.coerceAtLeast(1)
    val usedTokens = usage.usedTokens.coerceIn(0, contextWindow)
    val usedFraction = usedTokens.toFloat() / contextWindow.toFloat()
    val usedPercent = (usedFraction * 100).roundToInt()
    val remainingPercent = 100 - usedPercent
    var expanded by remember(usage) { mutableStateOf(false) }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val progressColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(36.dp),
        ) {
            Canvas(
                Modifier
                    .size(18.dp)
                    .semantics {
                        contentDescription = "上下文已使用 $usedPercent%，剩余 $remainingPercent%"
                    },
            ) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke,
                )
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = usedFraction * 360f,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(
                Modifier.widthIn(min = 220.dp, max = 300.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("上下文窗口", style = MaterialTheme.typography.labelLarge)
                Text(
                    "$remainingPercent% 剩余",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${formatTokenCount(usedTokens)} / ${formatTokenCount(contextWindow)} tokens 已使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatTokenCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
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
            shape = MaterialTheme.shapes.large,
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
                    IconButton(onClick = onDismiss) { Icon(CodexIcons.Close, contentDescription = "关闭") }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (state.remotePath != "/" && state.remotePath.isNotBlank()) {
                        item(key = "parent") {
                            ContextFileRow(
                                name = "..",
                                subtitle = "上级目录",
                                icon = CodexIcons.FolderOpen,
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
                                file.type == RemoteFileType.DIRECTORY -> CodexIcons.FolderOpen
                                file.isImage() -> CodexIcons.Image
                                else -> CodexIcons.Code
                            },
                            onClick = {
                                if (file.type == RemoteFileType.DIRECTORY) viewModel.browse(file.path) else onSelect(file)
                            },
                            onAdd = if (file.type == RemoteFileType.DIRECTORY) {
                                { onSelect(file) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteDirectoryPickerDialog(
    state: AppState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.84f).padding(18.dp).widthIn(max = 680.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("选择工作目录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.remotePath.ifBlank { "正在读取目录…" },
                            style = MonoTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(CodexIcons.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    if (state.remotePath != "/" && state.remotePath.isNotBlank()) {
                        item(key = "parent") {
                            ContextFileRow(
                                name = "..",
                                subtitle = "上级目录",
                                icon = CodexIcons.FolderOpen,
                                onClick = {
                                    viewModel.browse(state.remotePath.substringBeforeLast('/').ifBlank { "/" })
                                },
                            )
                        }
                    }
                    items(
                        items = state.remoteFiles.filter { it.type == RemoteFileType.DIRECTORY },
                        key = RemoteFile::path,
                    ) { directory ->
                        ContextFileRow(
                            name = directory.name,
                            subtitle = "目录",
                            icon = CodexIcons.Folder,
                            onClick = { viewModel.browse(directory.path) },
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onSelect(state.remotePath) },
                        enabled = state.remotePath.startsWith('/') && !state.loading,
                    ) {
                        Icon(CodexIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("使用此目录")
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
    onAdd: (() -> Unit)? = null,
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
        onAdd?.let {
            IconButton(onClick = it, modifier = Modifier.size(36.dp)) {
                Icon(CodexIcons.Add, contentDescription = "添加文件夹")
            }
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

private fun SessionSummary.target(): SessionTarget =
    SessionTarget(id, name ?: preview.ifBlank { "未命名会话" })

internal fun ThreadDetail.displayTitle(sessions: List<SessionSummary>): String =
    name?.takeIf(String::isNotBlank)
        ?: sessions.firstOrNull { it.id == id }?.let { summary ->
            summary.name?.takeIf(String::isNotBlank)
                ?: summary.preview.takeIf(String::isNotBlank)
        }
        ?: messages.firstOrNull { it.role == MessageRole.USER && it.text.isNotBlank() }
            ?.text
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        ?: "未命名会话"

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

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")
