@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.data.isRemoteAbsolutePath
import dev.codexremote.app.data.joinRemotePath
import dev.codexremote.app.data.remotePathBreadcrumbs
import dev.codexremote.app.data.remotePathToFileUri
import dev.codexremote.app.data.remoteFileUriToPath
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.codexremote.app.data.ExtensionSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.codexremote.app.BuildConfig
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.OfficialChatSession
import dev.codexremote.app.data.frontendScope
import dev.codexremote.app.data.windowStateScope
import dev.codexremote.app.model.CodingTool
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.WorkspaceWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class OfficialSettingsConfirmation {
    var pending by mutableStateOf<Pair<String, JsResult>?>(null)
        private set
    private var surfaceId: String? = null
    fun show(message: String, result: JsResult, visible: Boolean, surface: String? = null) {
        resolve(false)
        surfaceId = surface
        if (visible) pending = message to result else result.cancel()
    }
    fun cancelSurface(surface: String) { if (surfaceId == surface) resolve(false) }
    fun resolve(leave: Boolean) {
        val result = pending?.second
        pending = null
        surfaceId = null
        if (leave) result?.confirm() else result?.cancel()
    }
}

@Composable
internal fun OfficialSettingsConfirmationDialog(confirmation: OfficialSettingsConfirmation, visible: Boolean) {
    confirmation.pending?.takeIf { visible }?.let { (message, _) ->
        CodexDialog(onDismissRequest = { confirmation.resolve(false) }, title = { Text("离开设置？") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { confirmation.resolve(true) }) { Text("离开") } },
            dismissButton = { TextButton(onClick = { confirmation.resolve(false) }) { Text("取消") } })
    }
}

private fun JSONObject.version(name: String): String? = optString(name).takeIf { it.isNotBlank() && it != "null" }

@Composable
fun OfficialChatScreen(state: AppState, viewModel: MainViewModel, window: WorkspaceWindow,
    visible: Boolean = true, onCreateWindow: () -> Unit = {}, residentWindowIds: Set<String> = emptySet(),
    onWebViewRetainedChanged: (Boolean) -> Unit = {}) {
    val service = state.services.firstOrNull { it.id == window.serviceId }
    val link by viewModel.gatewayApi.link.collectAsStateWithLifecycle()
    val config = state.gatewayConfig
    if (service == null || config == null) {
        Column(Modifier.fillMaxSize()) {
            CodexTopBar(title = {
                WorkPageSelector(state, window, visible, residentWindowIds, onCreateWindow,
                    viewModel::openServiceWindow, viewModel::selectWindow,
                    onCloseWindow = viewModel::closeWindow, onSwitchTool = viewModel::switchWindowTool)
            })
            EmptyPane(title = "窗口的服务器暂不可用", icon = CodexIcons.Server, modifier = Modifier.weight(1f))
        }
        return
    }
    val toolName = CodingTool.title(window.toolId)
    val supported = CodingTool.fromId(window.toolId)?.supports(service) == true
    val scopeKey = windowStateScope(config, window)
    val jobs = rememberCoroutineScope()
    var extension by remember(scopeKey) { mutableStateOf<JSONObject?>(null) }
    var requestInProgress by remember(scopeKey) { mutableStateOf(false) }
    var status by remember(scopeKey) { mutableStateOf<String?>(null) }
    var error by remember(scopeKey) { mutableStateOf<String?>(null) }
    var menu by remember(scopeKey) { mutableStateOf(false) }
    var mouseMode by remember(scopeKey) { mutableStateOf(false) }
    var applyConfirmation by remember(scopeKey) { mutableStateOf(false) }
    var restartConfirmation by remember(scopeKey) { mutableStateOf(false) }
    var renaming by remember(scopeKey) { mutableStateOf(false) }
    var closing by remember(scopeKey) { mutableStateOf(false) }
    val restarting = window.id in state.busyWindowIds
    val workspace = window.workspace
    var pendingWorkspace by remember(scopeKey) { mutableStateOf<String?>(null) }
    var chooseWorkspace by remember(scopeKey) { mutableStateOf(false) }
    var panelBack by remember(scopeKey) { mutableStateOf<(() -> Unit)?>(null) }
    var rendererGeneration by remember(scopeKey) { mutableStateOf(0) }
    val installedVersion = extension?.version("version")
    val stagedVersion = extension?.version("stagedVersion")
    val updating = extension?.optBoolean("updating") == true
    val busy = requestInProgress || updating
    LaunchedEffect(visible) { if (!visible) menu = false }

    suspend fun presentStagedUpdate() {
        if (extension?.version("stagedVersion") == null) return
        if ((extension?.optInt("hosts", 0) ?: 0) > 0) {
            applyConfirmation = true
            return
        }
        val alreadyBusy = requestInProgress
        requestInProgress = true
        try {
            extension = viewModel.gatewayApi.applyExtension(service.id, confirmed = false, toolId = window.toolId)
            if (extension?.optBoolean("confirmationRequired") == true) applyConfirmation = true
            else status = "官方前端已更新"
        } finally { requestInProgress = alreadyBusy }
    }

    fun update() {
        if (busy) return
        requestInProgress = true
        error = null
        jobs.launch {
            try {
                extension = viewModel.gatewayApi.updateExtension(service.id, window.toolId)
                error = extension?.version("error")
                if (error == null && extension?.version("stagedVersion") != null && extension?.optBoolean("updating") != true) presentStagedUpdate()
                else if (extension?.optBoolean("updating") != true && error == null) status = "已是最新正式版"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { requestInProgress = false }
        }
    }

    LaunchedEffect(scopeKey, service.runtimeState) {
        if (!supported) { error = "Claude Code 首版仅支持 Linux Agent"; return@LaunchedEffect }
        try { extension = viewModel.gatewayApi.extensionStatus(service.id, window.toolId); error = extension?.version("error") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    LaunchedEffect(scopeKey) {
        // collect keeps completion/application alive when the updating flag becomes false.
        snapshotFlow { extension?.optBoolean("updating") == true }.collect { running ->
            if (!running) return@collect
            try {
                while (extension?.optBoolean("updating") == true) {
                    delay(1000)
                    try { extension = viewModel.gatewayApi.extensionStatus(service.id, window.toolId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message; delay(2000) }
                }
                error = extension?.version("error")
                if (error == null && extension?.version("stagedVersion") != null) presentStagedUpdate()
                else if (error == null) status = "已是最新正式版"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
        }
    }

    Scaffold(topBar = {
        CodexTopBar(title = {
            WorkPageSelector(state, window, visible, residentWindowIds, onCreateWindow,
                viewModel::openServiceWindow, viewModel::selectWindow,
                    onCloseWindow = viewModel::closeWindow, onSwitchTool = viewModel::switchWindowTool)
        }, navigationIcon = {
            if (panelBack != null) IconButton(onClick = { panelBack?.invoke() }) {
                AppIcon(CodexIcons.ArrowBack, contentDescription = "返回对话")
            }
        }, actions = {
                TextButton(onClick = viewModel::reconnectGateway, enabled = !link.checking,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.widthIn(min = 44.dp).semantics {
                        contentDescription = "网关连接：${link.endpoint.orEmpty()}，${if (link.checking) "连接中" else link.latencyMs?.let { "${it}毫秒，点击重连" } ?: "检测失败，点击重连"}"
                    }) {
                    if (link.checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(link.latencyMs?.let { "${it}ms" } ?: "重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (link.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconToggleButton(checked = mouseMode, onCheckedChange = { mouseMode = it },
                    enabled = installedVersion != null,
                    modifier = Modifier.width(40.dp).semantics { stateDescription = if (mouseMode) "已开启" else "已关闭" }) {
                    AppIcon(CodexIcons.MousePointer, contentDescription = "鼠标模式",
                        tint = if (mouseMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.windowMode) IconButton(onClick = { chooseWorkspace = true },
                    modifier = Modifier.width(40.dp),
                    enabled = !busy && !restarting && service.runtimeState == RuntimeState.CONNECTED) {
                    AppIcon(CodexIcons.FolderOpen, "更改工作目录")
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.width(40.dp)) { AppIcon(CodexIcons.More, contentDescription = "更多选项") }
                    CodexDropdownMenu(expanded = visible && menu, onDismissRequest = { menu = false }) {
                        Text("$toolName · ${installedVersion ?: "未安装"}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenuItem(text = { Text("检查并更新 $toolName") }, enabled = !busy && supported,
                            onClick = { menu = false; update() })
                        DropdownMenuItem(text = { Text("重新加载前端") }, enabled = installedVersion != null && !busy,
                            onClick = { menu = false; rendererGeneration += 1; status = "正在重新加载官方前端" })
                        if (state.windowMode) DropdownMenuItem(text = { Text("更改工作目录") }, enabled = !busy && !restarting,
                            onClick = { menu = false; chooseWorkspace = true })
                        if (state.windowMode) DropdownMenuItem(text = { Text("重命名窗口") }, enabled = !restarting,
                            onClick = { menu = false; renaming = true })
                        if (state.windowMode) DropdownMenuItem(text = { Text("关闭窗口") }, enabled = !restarting,
                            onClick = { menu = false; closing = true })
                        DropdownMenuItem(text = { Text("重启此窗口后端") },
                            enabled = !busy && !restarting && service.runtimeState == RuntimeState.CONNECTED,
                            onClick = { menu = false; restartConfirmation = true })
                        DropdownMenuItem(text = { Text("App 设置") }, leadingIcon = { AppIcon(CodexIcons.Settings, null) },
                            onClick = { menu = false; viewModel.setSection(MainSection.SETTINGS) })
                    }
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            if (restarting) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (busy) FrontendDownloadPane(extension?.optJSONObject("progress"))
            if (installedVersion != null) {
                key(scopeKey, installedVersion, workspace, rendererGeneration, state.windowRevisions[window.id]) {
                    OfficialWebView(scopeKey, window.id, service.id, service.home ?: "/", workspace,
                        state.theme, state.fontScale, visible, mouseMode, viewModel, { status = it },
                        { rendererGeneration += 1; status = "页面已退出，正在恢复连接" },
                        { panelBack = it }, menu || chooseWorkspace || applyConfirmation || restartConfirmation || renaming || closing || pendingWorkspace != null || restarting,
                        Modifier.weight(1f), onWebViewRetainedChanged, window.id, frontendScope(config, service.id, window.toolId), toolId = window.toolId)
                }
            } else if (!busy) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (extension == null && error == null) "正在查询前端状态" else "在服务器安装官方插件后打开会话。")
                Button(onClick = ::update, enabled = supported) { Text("检查并下载 $toolName") }
            }
        }
    }
    if (visible && chooseWorkspace) RemoteDirectoryDialog(viewModel.gatewayApi, service.id, workspace,
        onSelect = { path -> if (path != workspace) pendingWorkspace = path; chooseWorkspace = false },
        onDismiss = { chooseWorkspace = false })
    if (visible && renaming) RenameWorkspaceWindowDialog(window, viewModel) { renaming = false }
    if (visible && closing) CloseWorkspaceWindowDialog(window, viewModel) { closing = false }
    if (visible && pendingWorkspace != null) CodexDialog(
        onDismissRequest = { pendingWorkspace = null }, title = { Text("更改此窗口的工作目录？") },
        text = { Text("将停止此窗口的 $toolName 和扩展后端，在 ${pendingWorkspace.orEmpty()} 重新打开。正在运行的任务可能中断。") },
        confirmButton = { TextButton(onClick = {
            pendingWorkspace?.let { viewModel.changeWindowWorkspace(window.id, it) }
            pendingWorkspace = null
        }) { Text("更改并重启") } },
        dismissButton = { TextButton(onClick = { pendingWorkspace = null }) { Text("取消") } },
    )
    if (visible && restartConfirmation) CodexDialog(
        onDismissRequest = { restartConfirmation = false }, title = { Text("重启此窗口的后端？") },
        text = { Text("将重启此窗口的 $toolName 和扩展后端。正在运行的任务可能中断；后端已退出时也可通过此操作恢复。") },
        confirmButton = { TextButton(onClick = {
            restartConfirmation = false
            viewModel.restartWindow(window.id)
        }) { Text("重启并连接") } },
        dismissButton = { TextButton(onClick = { restartConfirmation = false }) { Text("取消") } },
    )
    if (visible && applyConfirmation && stagedVersion != null) CodexDialog(
        onDismissRequest = { applyConfirmation = false }, title = { Text("应用官方前端更新？") },
        text = { Text("版本 $stagedVersion 已准备好。应用更新将重启该服务器全部 ${extension?.optInt("hosts", 0) ?: 0} 个 $toolName 扩展宿主，现有连接和正在进行的任务可能中断。") },
        confirmButton = { TextButton(onClick = {
            applyConfirmation = false
            requestInProgress = true
            jobs.launch {
                try {
                    extension = viewModel.gatewayApi.applyExtension(service.id, confirmed = true, toolId = window.toolId)
                    status = "官方前端已更新"
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message }
                finally { requestInProgress = false }
            }
        }) { Text("应用并重启") } },
        dismissButton = { TextButton(onClick = { applyConfirmation = false }) { Text("稍后") } },
    )
}

@Composable
private fun FrontendDownloadPane(progress: JSONObject?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val stage = when (progress?.optString("stage")) {
            "downloading" -> "正在下载官方插件"
            "extracting" -> "正在解压官方插件"
            "verifying" -> "正在校验官方插件"
            "ready" -> "官方插件已准备好"
            else -> "正在检查官方插件更新"
        }
        Text(stage, style = MaterialTheme.typography.bodyMedium)
        val bytes = progress?.optLong("downloadedBytes", 0) ?: 0
        val total = progress?.takeIf { !it.isNull("totalBytes") }?.optLong("totalBytes", 0)?.takeIf { it > 0 }
        if (total == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
        if (progress?.optString("stage") == "downloading") Text(
            "${transferSize(bytes)} / ${total?.let(::transferSize) ?: "总大小未知"} · ${transferSize(progress.optDouble("bytesPerSecond", 0.0).toLong())}/s",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun transferSize(bytes: Long): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit += 1 }
    return if (unit == 0) "$bytes B" else java.lang.String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

private data class HostNotification(val method: String, val params: JSONObject, val result: CompletableDeferred<String?>)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficialWebView(
    scope: String, clientId: String, serviceId: String, home: String, workspace: String,
    theme: AppTheme, fontScale: Float, visible: Boolean, mouseMode: Boolean, viewModel: MainViewModel,
    onStatus: (String?) -> Unit, onRendererGone: () -> Unit, onPanelBackChanged: ((() -> Unit)?) -> Unit,
    nativeModalOpen: Boolean, modifier: Modifier, onWebViewRetainedChanged: (Boolean) -> Unit,
    windowId: String, resourceScope: String, toolId: String,
) {
    val context = LocalContext.current
    val pointerContainers = remember { mutableStateMapOf<String, MouseModeWebViewContainer>() }
    WebViewRetentionEffect(pointerContainers["main"] != null, onWebViewRetainedChanged)
    var panels by remember { mutableStateOf<List<ExtensionSurface>>(emptyList()) }
    var preview by remember { mutableStateOf<FilePreview?>(null) }
    var notification by remember { mutableStateOf<HostNotification?>(null) }
    var saveRequest by remember { mutableStateOf<Pair<JSONObject, CompletableDeferred<String?>>?>(null) }
    var externalUrl by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    var inputRequest by remember { mutableStateOf<Pair<JSONObject, CompletableDeferred<String?>>?>(null) }
    var quickPickRequest by remember { mutableStateOf<Pair<JSONObject, CompletableDeferred<Any?>>?>(null) }
    var textPreview by remember { mutableStateOf<JSONObject?>(null) }
    val confirmation = remember { OfficialSettingsConfirmation() }
    val pickFiles = rememberOfficialFilePicker(viewModel.gatewayApi, serviceId, home, workspace, visible, onStatus)
    val currentPickFiles by rememberUpdatedState(pickFiles)
    val session = remember {
        OfficialChatSession(context.applicationContext, viewModel.gatewayApi, viewModel.officialFrontends,
            serviceId, scope, clientId, workspace, onStatus, { preview = it }, { currentPickFiles(it) },
            { params ->
                check(saveRequest == null) { "保存对话框已打开" }
                val result = CompletableDeferred<String?>()
                saveRequest = params to result
                try { result.await() } finally { if (saveRequest?.second === result) saveRequest = null }
            }, { method, params ->
                check(notification == null) { "通知对话框已打开" }
                val result = CompletableDeferred<String?>()
                notification = HostNotification(method, params, result)
                try { result.await() } finally { if (notification?.result === result) notification = null }
            }, { url ->
                check(externalUrl == null) { "链接对话框已打开" }
                require(Uri.parse(url).scheme in listOf("http", "https")) { "不支持的链接类型" }
                val result = CompletableDeferred<Boolean>()
                externalUrl = url to result
                try { result.await() } finally { if (externalUrl?.second === result) externalUrl = null }
            }, { panels = it }, resourceScope = resourceScope, toolId = toolId,
            onInput = { params ->
                check(inputRequest == null) { "输入对话框已打开" }
                val result = CompletableDeferred<String?>()
                inputRequest = params to result
                try { result.await() } finally { if (inputRequest?.second === result) inputRequest = null }
            }, onQuickPick = { params ->
                check(quickPickRequest == null) { "选择对话框已打开" }
                val result = CompletableDeferred<Any?>()
                quickPickRequest = params to result
                try { result.await() } finally { if (quickPickRequest?.second === result) quickPickRequest = null }
            }, onOpenText = { textPreview = it })
    }
    DisposableEffect(session, windowId) {
        val unregister = viewModel.windowConnections.register(windowId, session::setSuspended)
        onDispose { unregister() }
    }
    DisposableEffect(session) {
        onDispose {
            confirmation.resolve(false)
            saveRequest?.second?.cancel()
            notification?.result?.cancel()
            externalUrl?.second?.cancel()
            inputRequest?.second?.cancel()
            quickPickRequest?.second?.cancel()
            session.close()
            android.util.Log.d("OfficialCodex", "resource-cache ${viewModel.officialFrontends.resources.diagnostics()} wireBytes=${viewModel.gatewayApi.resourceTrafficBytes()}")
            onPanelBackChanged(null)
        }
    }
    LaunchedEffect(visible) {
        session.setVisible(visible)
        if (!visible) confirmation.resolve(false)
    }
    if (!remember { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }) {
        Text("请更新 Android System WebView 后使用官方前端。", modifier = modifier.padding(20.dp))
        return
    }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboard = LocalSoftwareKeyboardController.current
    val topPanel = panels.lastOrNull()
    val modalOpen = nativeModalOpen || preview != null || notification != null || saveRequest != null || externalUrl != null ||
        inputRequest != null || quickPickRequest != null || textPreview != null || confirmation.pending != null
    LaunchedEffect(topPanel?.id, visible, keyboardVisible, modalOpen) {
        onPanelBackChanged(if (visible && topPanel != null) ({
            if (keyboardVisible) keyboard?.hide() else if (!modalOpen) session.closeSurface(topPanel.id)
        }) else null)
    }
    BackHandler(enabled = visible && topPanel != null && !keyboardVisible && !modalOpen) {
        topPanel?.let { session.closeSurface(it.id) }
    }
    Column(modifier.fillMaxSize()) {
        if (mouseMode && topPanel?.kind != "diff") {
            val target = pointerContainers[topPanel?.id ?: "main"]
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("滑动移动 · 轻点点击 · 双指滚动", style = MaterialTheme.typography.labelSmall)
                    Text("悬停显示更多操作 · 右侧箭头切换焦点", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { target?.navigateFocus(false) }, enabled = visible && target != null && !modalOpen) {
                    AppIcon(CodexIcons.ChevronUp, "聚焦上一项")
                }
                IconButton(onClick = { target?.navigateFocus(true) }, enabled = visible && target != null && !modalOpen) {
                    AppIcon(CodexIcons.ChevronDown, "聚焦下一项")
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            OfficialSurfaceWebView(session, "main", serviceId, theme, fontScale, visible && panels.isEmpty(), mouseMode && !modalOpen,
                confirmation, viewModel, onStatus, onRendererGone, {
                    if (it == null) pointerContainers.remove("main") else pointerContainers["main"] = it
                })
            panels.forEachIndexed { index, panel -> key(panel.id) {
                if (panel.kind == "diff") {
                    if (visible && index == panels.lastIndex) androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                        OfficialDiffPane(panel) { session.surfaceAction(panel.id, it) }
                    }
                } else OfficialSurfaceWebView(session, panel.id, serviceId, theme, fontScale, visible && index == panels.lastIndex, mouseMode && !modalOpen,
                        confirmation, viewModel, onStatus, onRendererGone, { if (it == null) pointerContainers.remove(panel.id) else pointerContainers[panel.id] = it })
            } }
        }
    }
    OfficialSettingsConfirmationDialog(confirmation, visible)
    inputRequest?.takeIf { visible }?.let { (params, result) -> OfficialInputDialog(params) { result.complete(it) } }
    quickPickRequest?.takeIf { visible }?.let { (params, result) -> OfficialQuickPickDialog(params) { result.complete(it) } }
    textPreview?.takeIf { visible }?.let { params -> OfficialTextDialog(params) { textPreview = null } }
    notification?.takeIf { visible }?.let { item ->
        val actions = item.params.optJSONArray("items") ?: item.params.optJSONArray("actions")
        CodexDialog(onDismissRequest = { item.result.complete(null) }, title = { Text(CodingTool.title(toolId)) },
            text = { Text(item.params.optString("message")) },
            confirmButton = {
                if (actions == null || actions.length() == 0) TextButton(onClick = { item.result.complete(null) }) { Text("关闭") }
                else for (i in 0 until actions.length()) {
                    val action = actions.opt(i)
                    val title = (action as? JSONObject)?.optString("title") ?: action.toString()
                    TextButton(onClick = { item.result.complete(title) }) { Text(title) }
                }
            }, dismissButton = { if (actions != null && actions.length() > 0) TextButton(onClick = { item.result.complete(null) }) { Text("取消") } })
    }
    saveRequest?.takeIf { visible }?.let { (params, result) ->
        var path by remember(result) { mutableStateOf(params.version("defaultUri")?.let(::remoteFileUriToPath) ?: joinRemotePath(workspace, "untitled")) }
        CodexDialog(onDismissRequest = { result.complete(null) }, title = { Text("保存到服务器") },
            text = { CodexTextField(path, { path = it }, label = "绝对路径") },
            confirmButton = { TextButton(onClick = { result.complete(remotePathToFileUri(path)) }, enabled = isRemoteAbsolutePath(path) && !path.endsWith('/') && !path.endsWith('\\') && remotePathBreadcrumbs(path).size > 1) { Text("保存") } },
            dismissButton = { TextButton(onClick = { result.complete(null) }) { Text("取消") } })
    }
    externalUrl?.takeIf { visible }?.let { (url, result) ->
        CodexDialog(onDismissRequest = { result.complete(false) }, title = { Text("打开链接") },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(url) } },
            confirmButton = { TextButton(onClick = {
                runCatching { session.openExternal(url) }.onSuccess { result.complete(it) }
                    .onFailure { onStatus(it.message); result.complete(false) }
            }) { Text("在浏览器打开") } },
            dismissButton = { TextButton(onClick = { result.complete(false) }) { Text("取消") } })
    }
    preview?.let { FilePreviewDialog(it, onDismiss = { preview = null }, api = viewModel.gatewayApi, serviceId = serviceId, visible = visible) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficialSurfaceWebView(session: OfficialChatSession, surfaceId: String, serviceId: String,
    theme: AppTheme, fontScale: Float, visible: Boolean, mouseMode: Boolean, confirmation: OfficialSettingsConfirmation,
    viewModel: MainViewModel, onStatus: (String?) -> Unit, onRendererGone: () -> Unit,
    onPointerContainerChanged: (MouseModeWebViewContainer?) -> Unit) {
    val context = LocalContext.current
    val effectiveFontScale = fontScale * LocalDensity.current.fontScale
    val currentTheme by rememberUpdatedState(theme)
    val currentScale by rememberUpdatedState(effectiveFontScale)
    val currentVisible by rememberUpdatedState(visible)
    val currentMouseMode by rememberUpdatedState(mouseMode && visible)
    val background = MaterialTheme.colorScheme.background.toArgb()
    val cursorForeground = MaterialTheme.colorScheme.onSurface.toArgb()
    var browser by remember { mutableStateOf<WebView?>(null) }
    var pointerContainer by remember { mutableStateOf<MouseModeWebViewContainer?>(null) }
    var script by remember { mutableStateOf<ScriptHandler?>(null) }
    var pageStartedAt by remember { mutableStateOf(0L) }
    val fileInput = remember { OfficialFileInput() }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileInput.complete(OfficialFileInput.selectedUris(result.resultCode, result.data))
    }
    DisposableEffect(session, surfaceId) { onDispose {
        confirmation.cancelSurface(surfaceId)
        fileInput.cancel()
        pointerContainer?.cancelPointer()
        onPointerContainerChanged(null)
        script?.remove()
        browser?.let { session.detachSurface(surfaceId, it); it.destroy() }
    } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        MouseModeWebViewContainer(context, WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser = this
            setBackgroundColor(background)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            addJavascriptInterface(session.bridge(surfaceId), "CodexExtensionBridge")
            var originHost: String? = null
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    if (!currentVisible || params.mode !in listOf(FileChooserParams.MODE_OPEN, FileChooserParams.MODE_OPEN_MULTIPLE)) {
                        callback.onReceiveValue(null)
                        return true
                    }
                    if (fileInput.begin(callback, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)) {
                        try { filePicker.launch(params.createIntent()) }
                        catch (failure: Exception) { fileInput.complete(null); onStatus("无法打开文件选择器：${failure.message}") }
                    }
                    return true
                }
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    confirmation.show(message, result, currentVisible, surfaceId)
                    return true
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) android.util.Log.d("OfficialCodex", "${message.messageLevel()}: ${message.message().take(1000)}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    android.util.Log.d("OfficialCodex", "webview-ready surface=$surfaceId elapsedMs=${android.os.SystemClock.elapsedRealtime() - pageStartedAt}")
                    pointerContainer?.onDocumentReady()
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    fileInput.cancel()
                    session.close()
                    pointerContainer?.cancelPointer()
                    script?.remove()
                    script = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    browser = null
                    onPointerContainerChanged(null)
                    onRendererGone()
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == originHost) return false
                    if (request.isForMainFrame && request.hasGesture()) session.requestExternal(request.url.toString())
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return officialWebViewResource(request.url, originHost, load = { version, path ->
                        val packageSha256 = session.packageSha256
                        viewModel.officialFrontends.resources.load(session.resourceScope, packageSha256, version, path) {
                            viewModel.gatewayApi.extensionResource(serviceId, version, path, packageSha256, session.toolId)
                        }
                    }, onFailure = session::resourceFailed)
                }
            }
            val bridgeSource = context.assets.open("bridge.js").bufferedReader().use { it.readText() }
            val themeCss = context.assets.open("theme.css").bufferedReader().use { it.readText() }
            session.attachSurface(surfaceId, this) { origin, html, documentId ->
                pageStartedAt = android.os.SystemClock.elapsedRealtime()
                fileInput.cancel()
                confirmation.cancelSurface(surfaceId)
                pointerContainer?.cancelPointer()
                originHost = Uri.parse(origin).host
                script?.remove()
                val config = JSONObject().put("documentId", documentId).put("theme", currentTheme.id)
                    .put("fontScale", currentScale).put("themeCss", themeCss)
                    .put("mouseMode", currentMouseMode && pointerContainer?.mouseModeActive == true)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    script = WebViewCompat.addDocumentStartJavaScript(this,
                        "window.__codexExtensionConfig = $config;\n$bridgeSource", setOf(origin))
                    // webview.html belongs to the official extension. Do not rewrite or inject into it.
                    loadDataWithBaseURL("${origin.trimEnd('/')}/", html, "text/html", "UTF-8", null)
                } else onStatus("当前 Android System WebView 不支持页面初始化，请更新系统 WebView 后重试。")
            }
        }).also { pointerContainer = it; onPointerContainerChanged(it) }
    }, update = { container ->
        val webView = container.webView
        container.setCursorColors(cursorForeground, background)
        container.mouseModeEnabled = mouseMode && visible
        if (!visible && webView.hasFocus()) webView.clearFocus()
        container.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        webView.setBackgroundColor(background)
        webView.evaluateJavascript("window.__codexExtensionHost?.setTheme(${JSONObject.quote(theme.id)}, $effectiveFontScale)", null)
    })
}
