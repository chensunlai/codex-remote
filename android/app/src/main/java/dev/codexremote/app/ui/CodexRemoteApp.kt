@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.codexremote.app.ui

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
    var windowsOpen by remember(state.gatewayConfig?.let { frontendScope(it, "") }) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        RetainedServerPanes(
            identity = state.gatewayConfig?.let { frontendScope(it, "") },
            availableIds = state.windows.map { it.id }.toSet(),
            selectedId = state.selectedWindowId,
            visible = chatVisible,
            maxPages = state.maxChatPages,
        ) { windowId, active ->
            val window = state.windows.first { it.id == windowId }
            OfficialChatScreen(state, viewModel, window, visible = active && foreground && !windowsOpen,
                onShowWindows = { windowsOpen = true },
                onWebViewRetainedChanged = { retained ->
                    if (retained) residentWebViews[windowId] = true else residentWebViews.remove(windowId)
                })
        }
        if (chatVisible && state.windows.none { it.id == state.selectedWindowId }) {
            Column(Modifier.fillMaxSize()) {
                CodexTopBar(title = { Text("工作窗口") }, actions = {
                    IconButton(onClick = { windowsOpen = true }) { AppIcon(CodexIcons.Add, "新建窗口") }
                })
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = { windowsOpen = true }) { Text("打开工作窗口") }
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
    if (windowsOpen) WorkspaceWindows(state, viewModel, residentWebViews.keys.toSet(), onDismiss = { windowsOpen = false })
}
