@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.codexremote.app.ui

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.R
import dev.codexremote.app.data.frontendScope
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.CodingTool
import dev.codexremote.app.model.RuntimeState
import java.time.LocalTime

private data class NavigationDestination(
    val section: MainSection,
    val label: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    NavigationDestination(MainSection.SESSIONS, R.string.sessions, CodexIcons.Messages),
    NavigationDestination(MainSection.TERMINAL, R.string.terminal, CodexIcons.Terminal),
    NavigationDestination(MainSection.FILES, R.string.files, CodexIcons.Folder),
    NavigationDestination(MainSection.SERVICES, R.string.services, CodexIcons.Server),
)

@Composable
fun CodexRemoteApp(state: AppState, viewModel: MainViewModel) {
    val terminalEnabled = state.services.firstOrNull { it.id == state.selectedServiceId }?.supportsTerminal != false
    if (!state.configured) {
        GatewaySetupScreen(
            initial = state.gatewayConfig,
            loading = state.connecting,
            error = state.error,
            onSave = viewModel::configureGateway,
            onClearError = viewModel::clearError,
            allowHttp = state.allowHttp, multipleEndpoints = state.multipleEndpoints,
            onExperiments = viewModel::setExperiments,
            customWindowLimit = state.customWindowLimit, maxChatPages = state.maxChatPages,
            customCacheLimit = state.customCacheLimit, resourceCacheBytes = state.resourceCacheBytes,
            onCustomWindowLimitChange = viewModel::setCustomWindowLimit, onMaxChatPagesChange = viewModel::setMaxChatPages,
            onCustomCacheLimitChange = viewModel::setCustomCacheLimit, onResourceCacheBytesChange = viewModel::setResourceCacheBytes,
            windowMode = state.windowMode, onWindowModeChange = viewModel::setWindowMode,
        )
        return
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        if (expanded) {
            Row(Modifier.fillMaxSize().navigationBarsPadding()) {
                NavigationRail(modifier = Modifier.width(80.dp), containerColor = MaterialTheme.colorScheme.background) {
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = state.section == destination.section,
                            enabled = destination.section != MainSection.TERMINAL || terminalEnabled,
                            colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent),
                            onClick = { viewModel.setSection(destination.section) },
                            icon = { AppIcon(destination.icon, contentDescription = stringResource(destination.label)) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = state.section == MainSection.SETTINGS,
                        colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent),
                        onClick = { viewModel.setSection(MainSection.SETTINGS) },
                        icon = { AppIcon(CodexIcons.Settings, contentDescription = stringResource(R.string.settings)) },
                        label = { Text(stringResource(R.string.settings)) },
                    )
                }
                VerticalDivider()
                MainContent(
                    state = state,
                    viewModel = viewModel,
                    snackbar = snackbar,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!WindowInsets.isImeVisible) {
                        Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().selectableGroup()) {
                                destinations.forEach { destination ->
                                    val selected = state.section == destination.section
                                    val enabled = destination.section != MainSection.TERMINAL || terminalEnabled
                                    val color = when {
                                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        selected -> MaterialTheme.colorScheme.onSurface
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Column(
                                        Modifier.weight(1f).heightIn(min = 56.dp)
                                            .selectable(selected, enabled = enabled, role = Role.Tab, onClick = { viewModel.setSection(destination.section) })
                                            .padding(vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                    ) {
                                        Box(Modifier.size(width = 36.dp, height = 26.dp)
                                            .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, MaterialTheme.shapes.small),
                                            contentAlignment = Alignment.Center) {
                                            AppIcon(destination.icon, contentDescription = null, tint = color)
                                        }
                                        Text(stringResource(destination.label), style = MaterialTheme.typography.labelSmall, color = color)
                                    }
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                MainContent(
                    state = state,
                    viewModel = viewModel,
                    snackbar = snackbar,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                )
            }
        }
    }

}

@Composable
private fun MainContent(
    state: AppState,
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val chatVisible = state.section == MainSection.SESSIONS
    val residentWebViews = remember(state.gatewayConfig?.let { frontendScope(it, "") }) {
        mutableStateMapOf<String, Boolean>()
    }
    var creatingWindow by remember(state.gatewayConfig?.let { frontendScope(it, "") }) { mutableStateOf(false) }
    var initialTool by remember { mutableStateOf(CodingTool.CODEX) }
    var welcomePlayed by rememberSaveable { mutableStateOf(false) }
    val createDefaultWindow = { initialTool = CodingTool.CODEX; creatingWindow = true }
    LaunchedEffect(state.windowMode) { if (!state.windowMode) creatingWindow = false }
    Box(modifier.fillMaxSize()) {
        RetainedServerPanes(
            identity = state.gatewayConfig?.let { frontendScope(it, "") },
            availableIds = state.windows.map { it.id }.toSet(),
            selectedId = state.selectedWindowId,
            visible = chatVisible,
            maxPages = state.maxChatPages,
        ) { windowId, active ->
            val window = state.windows.first { it.id == windowId }
            OfficialChatScreen(state, viewModel, window, visible = active && foreground && !creatingWindow,
                onCreateWindow = createDefaultWindow,
                residentWindowIds = residentWebViews.keys.toSet(),
                onWebViewRetainedChanged = { retained ->
                    if (retained) residentWebViews[windowId] = true else residentWebViews.remove(windowId)
                })
        }
        if (chatVisible && state.windows.none { it.id == state.selectedWindowId }) {
            Column(Modifier.fillMaxSize()) {
                CodexTopBar(title = {
                    WorkPageSelector(state, null, foreground, residentWebViews.keys.toSet(),
                        createDefaultWindow, viewModel::openServiceWindow, viewModel::selectWindow,
                    onCloseWindow = viewModel::closeWindow, onSwitchTool = viewModel::switchWindowTool)
                }, actions = {
                    if (state.windowMode) IconButton(onClick = createDefaultWindow) { AppIcon(CodexIcons.Add, "新建窗口") }
                })
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.windowMode) WorkspaceWelcome(state, welcomePlayed,
                        onShown = { welcomePlayed = true },
                        onCreateWindow = { initialTool = it; creatingWindow = true },
                        onShowServers = { viewModel.setSection(MainSection.SERVICES) })
                    else Button(onClick = {
                        state.selectedServiceId?.let(viewModel::openServiceWindow)
                            ?: viewModel.setSection(MainSection.SERVICES)
                    }) { Text(if (state.selectedServiceId == null) "选择服务器" else "打开对话") }
                }
            }
        }
        when (state.section) {
            MainSection.SERVICES -> ServicesScreen(state, viewModel)
            MainSection.SESSIONS -> Unit
            MainSection.TERMINAL -> TerminalScreen(state, viewModel)
            MainSection.FILES -> FilesScreen(state, viewModel)
            MainSection.SETTINGS -> SettingsScreen(state, viewModel)
        }
        Column(Modifier.fillMaxWidth()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
    if (state.windowMode && creatingWindow) NewWorkspaceWindowDialog(state, viewModel,
        onDismiss = { creatingWindow = false }, onCreated = { creatingWindow = false }, initialTool = initialTool)
}

@Composable
private fun WorkspaceWelcome(
    state: AppState,
    entrancePlayed: Boolean,
    onShown: () -> Unit,
    onCreateWindow: (CodingTool) -> Unit,
    onShowServers: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val context = LocalContext.current
    val animate = remember {
        !entrancePlayed && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    var entered by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) { onShown(); entered = true }
    val iconProgress by animateFloatAsState(if (entered) 1f else 0f,
        tween(if (animate) 320 else 0, easing = FastOutSlowInEasing), label = "welcomeIcon")
    val textProgress by animateFloatAsState(if (entered) 1f else 0f,
        tween(if (animate) 320 else 0, if (animate) 80 else 0, FastOutSlowInEasing), label = "welcomeText")
    val actionProgress by animateFloatAsState(if (entered) 1f else 0f,
        tween(if (animate) 320 else 0, if (animate) 160 else 0, FastOutSlowInEasing), label = "welcomeActions")
    val offset = with(LocalDensity.current) { 12.dp.toPx() }
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..10 -> "早上好"
            in 11..12 -> "中午好"
            in 13..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "夜深了"
        }
    }
    val connecting = state.loading && state.services.isEmpty()
    val noServers = state.services.isEmpty()
    val onlineServers = state.services.filter { it.runtimeState == RuntimeState.CONNECTED }
    val allOffline = !noServers && onlineServers.isEmpty()
    val title = when {
        connecting -> "正在连接服务器…"
        noServers -> "先接上一台服务器吧"
        allOffline -> "服务器都在休息"
        else -> "今天想做点什么？"
    }
    val subtitle = when {
        connecting -> null
        noServers -> "在服务器上启动 Agent，使用和 App 相同的访问令牌"
        allOffline -> "Agent 重新连上后就能开新窗口了"
        else -> "打开一个新窗口，选好服务器和目录就能开始"
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 32.dp).padding(top = 28.dp, bottom = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(112.dp).graphicsLayer {
                    alpha = iconProgress
                    scaleX = 0.92f + 0.08f * iconProgress
                    scaleY = scaleX
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.requiredSize(168.dp).background(
                        Brush.radialGradient(listOf(colors.tertiary.copy(alpha = 0.16f), Color.Transparent)), CircleShape))
                    Image(painterResource(R.drawable.ic_launcher_monochrome), contentDescription = null,
                        modifier = Modifier.size(112.dp), colorFilter = ColorFilter.tint(colors.onSurface))
                }
                Spacer(Modifier.height(20.dp))
                Column(Modifier.graphicsLayer { alpha = textProgress; translationY = offset * (1f - textProgress) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(greeting, style = typography.labelLarge, color = colors.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(title, Modifier.semantics { heading() }, style = typography.headlineSmall,
                        color = colors.onSurface, textAlign = TextAlign.Center)
                    if (subtitle != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(subtitle, style = typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                Spacer(Modifier.height(28.dp))
                Column(Modifier.graphicsLayer { alpha = actionProgress; translationY = offset * (1f - actionProgress) },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        connecting -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        noServers || allOffline -> OutlinedButton(onClick = onShowServers) {
                            Text(if (noServers) "去看看服务器" else "查看服务器")
                        }
                        else -> {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CodingTool.entries.forEach { tool ->
                                    val enabled = tool != CodingTool.CLAUDE || onlineServers.any { tool.supports(it) }
                                    FilledTonalButton(onClick = { onCreateWindow(tool) }, enabled = enabled,
                                        modifier = Modifier.height(48.dp).semantics {
                                            if (!enabled) contentDescription = "Claude Code，需要在线的 Linux 服务器"
                                        }, contentPadding = PaddingValues(horizontal = 20.dp)) {
                                        CodingToolIcon(tool.id, Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (tool == CodingTool.CODEX) "Codex" else "Claude Code")
                                    }
                                }
                            }
                            val offlineWindows = state.windows.count { window -> onlineServers.none { it.id == window.serviceId } }
                            if (offlineWindows > 0) {
                                Spacer(Modifier.height(16.dp))
                                Text("$offlineWindows 个窗口在离线服务器上，连上后会回来", style = typography.bodySmall,
                                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
