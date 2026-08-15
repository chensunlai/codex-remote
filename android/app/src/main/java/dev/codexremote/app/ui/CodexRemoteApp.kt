@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.R
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.MainSection

private data class NavigationDestination(
    val section: MainSection,
    val label: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    NavigationDestination(MainSection.SESSIONS, R.string.sessions, Icons.Outlined.Forum),
    NavigationDestination(MainSection.TERMINAL, R.string.terminal, Icons.Outlined.Terminal),
    NavigationDestination(MainSection.FILES, R.string.files, Icons.Outlined.Folder),
    NavigationDestination(MainSection.SERVICES, R.string.services, Icons.Outlined.Dns),
)

@Composable
fun CodexRemoteApp(state: AppState, viewModel: MainViewModel) {
    if (!state.configured) {
        GatewaySetupScreen(
            initial = state.gatewayConfig,
            loading = state.loading,
            error = state.error,
            onSave = viewModel::configureGateway,
            onClearError = viewModel::clearError,
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
                NavigationRail(modifier = Modifier.width(88.dp)) {
                    destinations.forEach { destination ->
                        NavigationRailItem(
                            selected = state.section == destination.section,
                            onClick = { viewModel.setSection(destination.section) },
                            icon = { Icon(destination.icon, contentDescription = stringResource(destination.label)) },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    NavigationRailItem(
                        selected = state.section == MainSection.SETTINGS,
                        onClick = { viewModel.setSection(MainSection.SETTINGS) },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings)) },
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
                        NavigationBar {
                            destinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = state.section == destination.section,
                                    onClick = { viewModel.setSection(destination.section) },
                                    icon = { Icon(destination.icon, contentDescription = stringResource(destination.label)) },
                                    label = { Text(stringResource(destination.label)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                MainContent(
                    state = state,
                    viewModel = viewModel,
                    snackbar = snackbar,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    state.pendingRequests.firstOrNull()?.let { pending ->
        PendingRequestDialog(
            pending = pending,
            onDecision = { viewModel.respondToRequest(pending.requestId, it) },
            onAnswers = { viewModel.respondToInput(pending.requestId, it) },
            onPermission = { permissions, session ->
                viewModel.respondToPermission(pending.requestId, permissions, session)
            },
            onElicitation = { action, content ->
                viewModel.respondToElicitation(pending.requestId, action, content)
            },
        )
    }
}

@Composable
private fun MainContent(
    state: AppState,
    viewModel: MainViewModel,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        when (state.section) {
            MainSection.SERVICES -> ServicesScreen(state, viewModel)
            MainSection.SESSIONS -> SessionsScreen(state, viewModel)
            MainSection.TERMINAL -> TerminalScreen(state, viewModel)
            MainSection.FILES -> FilesScreen(state, viewModel)
            MainSection.SETTINGS -> SettingsScreen(state, viewModel)
        }
        Column(Modifier.fillMaxWidth()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}
