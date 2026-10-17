@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.codexremote.app.BuildConfig
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.OfficialChatSession
import dev.codexremote.app.data.OfficialFrontend
import dev.codexremote.app.data.OfficialVersion
import dev.codexremote.app.data.FrontendInstallProgress
import dev.codexremote.app.data.FrontendInstallStage
import dev.codexremote.app.data.frontendScope
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.objects
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.MainSection
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

private class OfficialSettingsNavigation {
    var inSettings by mutableStateOf(false)
    var exit: (() -> Unit)? = null
}

internal class OfficialSettingsConfirmation {
    var pending by mutableStateOf<Pair<String, JsResult>?>(null)
        private set

    fun show(message: String, result: JsResult, visible: Boolean) {
        resolve(false)
        if (visible) pending = message to result else result.cancel()
    }

    fun resolve(leave: Boolean) {
        val result = pending?.second
        pending = null
        if (leave) result?.confirm() else result?.cancel()
    }
}

@Composable
internal fun OfficialSettingsConfirmationDialog(confirmation: OfficialSettingsConfirmation, visible: Boolean) {
    confirmation.pending?.takeIf { visible }?.let { (message, _) ->
        CodexDialog(onDismissRequest = { confirmation.resolve(false) },
            title = { Text("离开设置？") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { confirmation.resolve(true) }) { Text("离开") } },
            dismissButton = { TextButton(onClick = { confirmation.resolve(false) }) { Text("取消") } })
    }
}

@Composable
internal fun OfficialSettingsBackHandler(visible: Boolean, inSettings: Boolean, modalOpen: Boolean, onBack: () -> Unit) {
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = visible && inSettings && !modalOpen && !keyboardVisible, onBack = onBack)
}

@Composable
fun OfficialChatScreen(state: AppState, viewModel: MainViewModel, visible: Boolean = true) {
    val service = state.services.firstOrNull { it.id == state.selectedServiceId }
    val config = state.gatewayConfig
    if (service == null || config == null) {
        EmptyPane(title = "选择在线服务器", icon = CodexIcons.Server, modifier = Modifier.fillMaxSize())
        return
    }
    val scopeKey = frontendScope(config, service.id)
    val store = viewModel.officialFrontends
    val jobs = rememberCoroutineScope()
    var mode by remember(scopeKey) { mutableStateOf(store.mode(scopeKey)) }
    var frontend by remember(scopeKey, mode) { mutableStateOf(store.cached(mode)) }
    var loading by remember(scopeKey) { mutableStateOf(false) }
    var status by remember(scopeKey) { mutableStateOf<String?>(null) }
    var error by remember(scopeKey) { mutableStateOf<String?>(null) }
    var chooseMode by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<OfficialVersion>?>(null) }
    var workspace by remember(scopeKey) { mutableStateOf(store.workspace(scopeKey, service.home ?: "/")) }
    var chooseWorkspace by remember { mutableStateOf(false) }
    var installProgress by remember(scopeKey) { mutableStateOf<FrontendInstallProgress?>(null) }
    var installJob by remember(scopeKey) { mutableStateOf<Job?>(null) }
    val settingsNavigation = remember(scopeKey, frontend?.version, frontend?.slot, workspace) { OfficialSettingsNavigation() }

    fun install(slot: String, version: OfficialVersion? = null) {
        if (loading) return
        loading = true
        error = null
        status = null
        frontend = null
        installProgress = null
        installJob = jobs.launch {
            try {
                val selected = version ?: store.versions().firstOrNull { !it.preRelease }
                    ?: error("官方暂无可用稳定版本")
                val installed = store.install(slot, selected) { progress -> jobs.launch { if (loading) installProgress = progress } }
                store.setMode(scopeKey, slot)
                mode = slot
                frontend = installed
                status = null
            } catch (cancelled: CancellationException) {
                frontend = store.cached(mode)
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message
                frontend = store.cached(mode)
            } finally {
                loading = false
                installProgress = null
                installJob = null
            }
        }
    }

    fun chooseSpecifiedVersion() {
        jobs.launch {
            loading = true
            runCatching { store.versions() }.onSuccess { choices = it }.onFailure { error = it.message }
            loading = false
        }
    }

    fun selectSlot(slot: String) {
        val cached = store.cached(slot)
        if (cached != null) {
            store.setMode(scopeKey, slot)
            mode = slot
            frontend = cached
            error = null
            status = null
        } else if (slot == "latest") install(slot)
        else chooseSpecifiedVersion()
    }

    LaunchedEffect(scopeKey) {
        if (frontend == null && mode == "latest") install("latest")
    }

    Scaffold(topBar = {
        CodexTopBar(
            title = {
                Text(service.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (settingsNavigation.inSettings) settingsNavigation.exit?.invoke()
                    else viewModel.setSection(MainSection.SERVICES)
                }) {
                    AppIcon(CodexIcons.ArrowBack, contentDescription = if (settingsNavigation.inSettings) "退出插件设置" else "切换服务器")
                }
            },
            actions = {
                IconButton(onClick = { chooseWorkspace = true }, enabled = !loading) {
                    AppIcon(CodexIcons.FolderOpen, contentDescription = "选择工作目录")
                }
                Box {
                    IconButton(onClick = { chooseMode = true }) {
                        AppIcon(CodexIcons.More, contentDescription = "更多选项")
                    }
                    CodexDropdownMenu(expanded = chooseMode, onDismissRequest = { chooseMode = false }) {
                        Text("官方前端 · ${frontend?.version ?: "未下载"}",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenuItem(text = { Text("最新") }, enabled = !loading,
                            trailingIcon = { if (mode == "latest") AppIcon(CodexIcons.Check, null) },
                            onClick = { chooseMode = false; selectSlot("latest") })
                        DropdownMenuItem(text = { Text("指定版本") }, enabled = !loading,
                            trailingIcon = { if (mode == "specified") AppIcon(CodexIcons.Check, null) },
                            onClick = { chooseMode = false; selectSlot("specified") })
                        DropdownMenuItem(text = { Text("检查并下载最新稳定版") }, enabled = !loading,
                            onClick = { chooseMode = false; install("latest") })
                        DropdownMenuItem(text = { Text("更换指定版本") }, enabled = !loading,
                            onClick = { chooseMode = false; chooseSpecifiedVersion() })
                        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DropdownMenuItem(text = { Text("App 设置") },
                            leadingIcon = { AppIcon(CodexIcons.Settings, null) },
                            onClick = { chooseMode = false; viewModel.setSection(MainSection.SETTINGS) })
                    }
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            if (loading) {
                FrontendDownloadPane(installProgress, onCancel = { installJob?.cancel() }, canCancel = installJob != null)
            } else {
                frontend?.let { bundle ->
                    key(scopeKey, bundle.version, bundle.slot, workspace) {
                        OfficialWebView(bundle, scopeKey, store.clientId(scopeKey), service.id,
                            service.home ?: "/", workspace, service.name, state.theme, state.fontScale, visible,
                            chooseMode || choices != null || chooseWorkspace, settingsNavigation,
                            viewModel, { status = it }, Modifier.weight(1f))
                    }
                } ?: Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择官方前端版本后打开会话。")
                    TextButton(onClick = { install("latest") }) { Text("下载最新稳定版") }
                }
            }
        }
    }

    if (visible && chooseWorkspace) RemoteDirectoryDialog(viewModel.gatewayApi, service.id, workspace,
        onSelect = { path -> store.setWorkspace(scopeKey, path); workspace = path; chooseWorkspace = false },
        onDismiss = { chooseWorkspace = false })

    choices?.takeIf { visible }?.let { versions ->
        CodexDialog(
            onDismissRequest = { choices = null },
            title = { Text("指定官方前端版本") },
            text = {
                val supported = remember { store.supportedVersions() }
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(versions.sortedWith(compareBy<OfficialVersion> { it.version !in supported }.thenBy { it.preRelease }), key = { it.version }) { version ->
                        TextButton(onClick = { choices = null; install("specified", version) }, modifier = Modifier.fillMaxWidth()) {
                            Text(version.version + (if (version.preRelease) " · 预发布" else "") +
                                (if (version.version !in supported) " · 尚未适配" else ""))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choices = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun FrontendDownloadPane(progress: FrontendInstallProgress?, onCancel: () -> Unit, canCancel: Boolean) {
    Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val stage = when (progress?.stage) {
            FrontendInstallStage.CONNECTING -> "正在连接官方插件下载源"
            FrontendInstallStage.DOWNLOADING -> "正在下载官方前端"
            FrontendInstallStage.EXTRACTING -> "正在解压官方前端"
            FrontendInstallStage.VERIFYING -> "正在校验官方前端"
            null -> "正在查询官方版本"
        }
        Text(stage, style = MaterialTheme.typography.titleMedium)
        progress?.let { Text(it.version, style = MaterialTheme.typography.bodyMedium) }
        val fraction = progress?.takeIf { it.stage == FrontendInstallStage.DOWNLOADING }?.fraction
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        if (progress != null && progress.stage != FrontendInstallStage.CONNECTING) {
            Text("${transferSize(progress.downloadedBytes)} / ${progress.totalBytes?.let(::transferSize) ?: "总大小未知"}")
            if (progress.stage == FrontendInstallStage.DOWNLOADING) Text("${transferSize(progress.bytesPerSecond.toLong())}/s", style = MaterialTheme.typography.bodyMedium)
        }
        if (canCancel) TextButton(onClick = onCancel) { Text("取消下载") }
    }
}

private fun transferSize(bytes: Long): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit += 1 }
    return if (unit == 0) "$bytes B" else java.lang.String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficialWebView(
    frontend: OfficialFrontend,
    scope: String,
    clientId: String,
    serviceId: String,
    home: String,
    workspace: String,
    serviceName: String,
    theme: AppTheme,
    fontScale: Float,
    visible: Boolean,
    nativeModalOpen: Boolean,
    navigation: OfficialSettingsNavigation,
    viewModel: MainViewModel,
    onStatus: (String?) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val effectiveFontScale = fontScale * LocalDensity.current.fontScale
    val currentTheme by rememberUpdatedState(theme)
    val currentVisible by rememberUpdatedState(visible)
    val background = MaterialTheme.colorScheme.background.toArgb()
    var preview by remember { mutableStateOf<FilePreview?>(null) }
    var conflict by remember { mutableStateOf(false) }
    val confirmation = remember { OfficialSettingsConfirmation() }
    val pickFiles = rememberOfficialFilePicker(viewModel.gatewayApi, serviceId, home, workspace, visible, onStatus)
    val currentPickFiles by rememberUpdatedState(pickFiles)
    val session = remember { OfficialChatSession(context.applicationContext, viewModel.gatewayApi, serviceId, clientId,
        home, workspace, serviceName, onStatus, { preview = it }, { currentPickFiles(it) }, { conflict = true },
        { navigation.inSettings = it }) }
    val pageId = remember { UUID.randomUUID().toString() }
    val originHost = "s$scope.codex-remote.invalid"
    var browser by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(session) {
        navigation.exit = session::exitSettings
        viewModel.officialFrontends.retain(frontend)
        onDispose {
            confirmation.resolve(false)
            navigation.exit = null
            session.close()
            browser?.destroy()
            browser = null
            viewModel.officialFrontends.release(frontend)
        }
    }
    LaunchedEffect(visible) {
        if (!visible) confirmation.resolve(false)
    }
    OfficialSettingsBackHandler(visible, navigation.inSettings,
        nativeModalOpen || conflict || preview != null || confirmation.pending != null, session::exitSettings)
    AndroidView(modifier = modifier.fillMaxSize(), factory = {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            browser = this
            setBackgroundColor(background)
            session.setTheme(theme.id)
            session.setFontScale(effectiveFontScale)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            addJavascriptInterface(session.bridge, "CodexRemoteHost")
            webChromeClient = object : WebChromeClient() {
                override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    confirmation.show(message, result, currentVisible)
                    return true
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) android.util.Log.d("OfficialCodex", "${message.messageLevel()}: ${message.message().take(1000)}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    confirmation.resolve(false)
                    session.pageStarted()
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != originHost

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != originHost) return null
                    val relative = request.url.path.orEmpty().trimStart('/')
                    return runCatching {
                        val path = if (request.isForMainFrame) "index.html" else relative.ifBlank { "index.html" }
                        val mime = when {
                            path.endsWith(".mjs") || path.endsWith(".js") -> "text/javascript"
                            path.endsWith(".css") -> "text/css"
                            path.endsWith(".json") -> "application/json"
                            path.endsWith(".html") -> "text/html"
                            path.endsWith(".wasm") -> "application/wasm"
                            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.')) ?: "application/octet-stream"
                        }
                        val stream = when {
                            path.startsWith("__host/") -> context.assets.open(path.removePrefix("__host/"))
                            path == "index.html" -> {
                                val html = File(frontend.directory, path).readText()
                                val injection = """
                                    <meta name="codex-extension-version" content="${frontend.version}">
                                    <meta name="codex-session-id" content="$pageId">
                                    <meta name="codex-build-flavor" content="prod">
                                    <meta name="codex-remote-storage-key" content="$scope">
                                    <link rel="stylesheet" href="/__host/theme.css">
                                    <script src="/__host/shim.js"></script>
                                    <script type="module" src="/__host/host.mjs"></script>
                                """.trimIndent()
                                ByteArrayInputStream(html.replace("<!-- PROD_BASE_TAG_HERE -->", "<base href=\"https://$originHost/\">")
                                    .replace("<!-- PROD_CSP_TAG_HERE -->", "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self'; worker-src 'self' blob:; media-src 'self' blob: data:; object-src 'none'; frame-src 'none'\">")
                                    .replace("<head>", "<head>$injection")
                                    .replace("<html", "<html data-codex-theme=\"${currentTheme.id}\"")
                                    .replace("<body>", "<body class=\"${if (currentTheme == AppTheme.DARK) "vscode-dark" else "vscode-light"}\" data-vscode-theme-kind=\"${if (currentTheme == AppTheme.DARK) "vscode-dark" else "vscode-light"}\">").toByteArray())
                            }
                            else -> {
                                val file = File(frontend.directory, path).canonicalFile
                                check(file.path.startsWith(frontend.directory.canonicalPath + File.separator))
                                file.inputStream()
                            }
                        }
                        WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-cache"), stream)
                    }.getOrElse {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                }
            }
            session.attach(this)
            loadUrl("https://$originHost/")
        }
    }, update = { browser ->
        if (!visible && browser.hasFocus()) browser.clearFocus()
        browser.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        browser.setBackgroundColor(background)
        session.setTheme(theme.id)
        session.setFontScale(effectiveFontScale)
    })
    OfficialSettingsConfirmationDialog(confirmation, visible)
    if (visible && conflict) CodexDialog(
        onDismissRequest = { conflict = false },
        title = { Text("会话正在被占用") },
        text = { Text("另一个 Codex 实例仍持有此会话，当前未能通过官方客户端协调连接到它。请确认电脑端 VS Code 已打开此会话、Agent 与插件使用同一用户和 Codex 目录。独立 CLI 若未参与协调，需要先释放会话后再重新打开。") },
        confirmButton = { TextButton(onClick = { conflict = false; browser?.reload() }) { Text("释放后重新打开") } },
        dismissButton = { TextButton(onClick = { conflict = false }) { Text("关闭") } },
    )
    preview?.takeIf { visible }?.let { FilePreviewDialog(it, onDismiss = { preview = null }) }

}

@Composable
internal fun RemoteDirectoryDialog(api: GatewayApi, serviceId: String, initialPath: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(initialPath) }
    var input by remember { mutableStateOf(initialPath) }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        busy = true
        error = null
        try {
            entries = api.files(serviceId, path).objects().filter { it.optString("type") == "directory" }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        finally { busy = false }
    }
    CodexDialog(onDismissRequest = onDismiss, title = { Text("远端工作目录") }, text = {
        Column {
            CodexTextField(input, { input = it }, label = "绝对路径")
            TextButton(onClick = { if (input.startsWith('/')) path = input }, enabled = !busy && input.startsWith('/')) { Text("打开路径") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) CircularProgressIndicator()
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                item {
                    TextButton(onClick = { path = path.trimEnd('/').substringBeforeLast('/').ifBlank { "/" }; input = path }, enabled = !busy && path != "/") {
                        Text("返回父目录")
                    }
                }
                items(entries, key = { it.getString("path") }) { entry ->
                    TextButton(onClick = { path = entry.getString("path"); input = path }, modifier = Modifier.fillMaxWidth()) { Text(entry.getString("name")) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSelect(path) }, enabled = !busy && error == null) { Text("使用此目录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
