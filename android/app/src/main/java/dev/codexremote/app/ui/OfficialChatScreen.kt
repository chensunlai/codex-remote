@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.codexremote.app.data.ExtensionSurface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.codexremote.app.data.objects
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.MainSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
    var extension by remember(scopeKey) { mutableStateOf<JSONObject?>(null) }
    var requestInProgress by remember(scopeKey) { mutableStateOf(false) }
    var status by remember(scopeKey) { mutableStateOf<String?>(null) }
    var error by remember(scopeKey) { mutableStateOf<String?>(null) }
    var menu by remember(scopeKey) { mutableStateOf(false) }
    var applyConfirmation by remember(scopeKey) { mutableStateOf(false) }
    var workspace by remember(scopeKey) { mutableStateOf(store.workspace(scopeKey, service.home ?: "/")) }
    var chooseWorkspace by remember(scopeKey) { mutableStateOf(false) }
    var panelBack by remember(scopeKey) { mutableStateOf<(() -> Unit)?>(null) }
    var rendererGeneration by remember(scopeKey) { mutableStateOf(0) }
    val installedVersion = extension?.version("version")
    val stagedVersion = extension?.version("stagedVersion")
    val updating = extension?.optBoolean("updating") == true
    val busy = requestInProgress || updating

    suspend fun presentStagedUpdate() {
        if (extension?.version("stagedVersion") == null) return
        if ((extension?.optInt("hosts", 0) ?: 0) > 0) {
            applyConfirmation = true
            return
        }
        val alreadyBusy = requestInProgress
        requestInProgress = true
        try {
            extension = viewModel.gatewayApi.applyExtension(service.id, confirmed = false)
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
                extension = viewModel.gatewayApi.updateExtension(service.id)
                error = extension?.version("error")
                if (error == null && extension?.version("stagedVersion") != null && extension?.optBoolean("updating") != true) presentStagedUpdate()
                else if (extension?.optBoolean("updating") != true && error == null) status = "已是最新正式版"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message }
            finally { requestInProgress = false }
        }
    }

    LaunchedEffect(scopeKey) {
        try { extension = viewModel.gatewayApi.extensionStatus(service.id); error = extension?.version("error") }
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
                    try { extension = viewModel.gatewayApi.extensionStatus(service.id) }
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
        CodexTopBar(title = { Text(service.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { panelBack?.invoke() ?: viewModel.setSection(MainSection.SERVICES) }) {
                AppIcon(CodexIcons.ArrowBack, contentDescription = if (panelBack != null) "返回对话" else "切换服务器")
            } }, actions = {
                IconButton(onClick = { chooseWorkspace = true }, enabled = !requestInProgress) {
                    AppIcon(CodexIcons.FolderOpen, contentDescription = "选择工作目录")
                }
                Box {
                    IconButton(onClick = { menu = true }) { AppIcon(CodexIcons.More, contentDescription = "更多选项") }
                    CodexDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Text("官方前端 · ${installedVersion ?: "未安装"}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        DropdownMenuItem(text = { Text("检查并更新前端") }, enabled = !busy,
                            onClick = { menu = false; update() })
                        DropdownMenuItem(text = { Text("App 设置") }, leadingIcon = { AppIcon(CodexIcons.Settings, null) },
                            onClick = { menu = false; viewModel.setSection(MainSection.SETTINGS) })
                    }
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            if (busy) FrontendDownloadPane(extension?.optJSONObject("progress"))
            if (installedVersion != null) {
                key(scopeKey, installedVersion, workspace, rendererGeneration) {
                    OfficialWebView(scopeKey, store.clientId(scopeKey), service.id, service.home ?: "/", workspace,
                        state.theme, state.fontScale, visible, viewModel, { status = it },
                        { rendererGeneration += 1; status = "页面已退出，正在恢复连接" },
                        { panelBack = it }, menu || chooseWorkspace || applyConfirmation, Modifier.weight(1f))
                }
            } else if (!busy) Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (extension == null && error == null) "正在查询前端状态" else "在服务器安装官方插件后打开会话。")
                TextButton(onClick = ::update) { Text("检查并更新前端") }
            }
        }
    }
    if (visible && chooseWorkspace) RemoteDirectoryDialog(viewModel.gatewayApi, service.id, workspace,
        onSelect = { path -> store.setWorkspace(scopeKey, path); workspace = path; chooseWorkspace = false },
        onDismiss = { chooseWorkspace = false })
    if (visible && applyConfirmation && stagedVersion != null) CodexDialog(
        onDismissRequest = { applyConfirmation = false }, title = { Text("应用官方前端更新？") },
        text = { Text("版本 $stagedVersion 已准备好。应用更新将重新启动服务器上的扩展宿主，现有连接和正在进行的任务可能中断。") },
        confirmButton = { TextButton(onClick = {
            applyConfirmation = false
            requestInProgress = true
            jobs.launch {
                try {
                    extension = viewModel.gatewayApi.applyExtension(service.id, confirmed = true)
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
    theme: AppTheme, fontScale: Float, visible: Boolean, viewModel: MainViewModel,
    onStatus: (String?) -> Unit, onRendererGone: () -> Unit, onPanelBackChanged: ((() -> Unit)?) -> Unit,
    nativeModalOpen: Boolean, modifier: Modifier,
) {
    val context = LocalContext.current
    var panels by remember { mutableStateOf<List<ExtensionSurface>>(emptyList()) }
    var preview by remember { mutableStateOf<FilePreview?>(null) }
    var notification by remember { mutableStateOf<HostNotification?>(null) }
    var saveRequest by remember { mutableStateOf<Pair<JSONObject, CompletableDeferred<String?>>?>(null) }
    var externalUrl by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
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
            }, { panels = it })
    }
    DisposableEffect(session) {
        onDispose {
            confirmation.resolve(false)
            saveRequest?.second?.cancel()
            notification?.result?.cancel()
            externalUrl?.second?.cancel()
            session.close()
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
    val modalOpen = nativeModalOpen || preview != null || notification != null || saveRequest != null || externalUrl != null || confirmation.pending != null
    LaunchedEffect(topPanel?.id, visible, keyboardVisible, modalOpen) {
        onPanelBackChanged(if (visible && topPanel != null) ({
            if (keyboardVisible) keyboard?.hide() else if (!modalOpen) session.closeSurface(topPanel.id)
        }) else null)
    }
    BackHandler(enabled = visible && topPanel != null && !keyboardVisible && !modalOpen) {
        topPanel?.let { session.closeSurface(it.id) }
    }
    Box(modifier.fillMaxSize()) {
        OfficialSurfaceWebView(session, "main", serviceId, theme, fontScale, visible && panels.isEmpty(), confirmation, viewModel, onStatus, onRendererGone)
        panels.forEachIndexed { index, panel -> key(panel.id) {
            OfficialSurfaceWebView(session, panel.id, serviceId, theme, fontScale, visible && index == panels.lastIndex, confirmation, viewModel, onStatus, onRendererGone)
        } }
    }
    OfficialSettingsConfirmationDialog(confirmation, visible)
    notification?.takeIf { visible }?.let { item ->
        val actions = item.params.optJSONArray("items") ?: item.params.optJSONArray("actions")
        CodexDialog(onDismissRequest = { item.result.complete(null) }, title = { Text("Codex") },
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
        var path by remember(result) { mutableStateOf(params.version("defaultUri")?.let { Uri.parse(it).path } ?: "$workspace/untitled") }
        CodexDialog(onDismissRequest = { result.complete(null) }, title = { Text("保存到服务器") },
            text = { CodexTextField(path, { path = it }, label = "绝对路径") },
            confirmButton = { TextButton(onClick = { result.complete(Uri.fromFile(java.io.File(path)).toString()) }, enabled = path.startsWith('/') && !path.endsWith('/')) { Text("保存") } },
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
    preview?.takeIf { visible }?.let { FilePreviewDialog(it, onDismiss = { preview = null }, api = viewModel.gatewayApi, serviceId = serviceId) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OfficialSurfaceWebView(session: OfficialChatSession, surfaceId: String, serviceId: String,
    theme: AppTheme, fontScale: Float, visible: Boolean, confirmation: OfficialSettingsConfirmation,
    viewModel: MainViewModel, onStatus: (String?) -> Unit, onRendererGone: () -> Unit) {
    val context = LocalContext.current
    val effectiveFontScale = fontScale * LocalDensity.current.fontScale
    val currentTheme by rememberUpdatedState(theme)
    val currentScale by rememberUpdatedState(effectiveFontScale)
    val currentVisible by rememberUpdatedState(visible)
    val background = MaterialTheme.colorScheme.background.toArgb()
    var browser by remember { mutableStateOf<WebView?>(null) }
    var script by remember { mutableStateOf<ScriptHandler?>(null) }
    DisposableEffect(session, surfaceId) { onDispose {
        confirmation.cancelSurface(surfaceId)
        script?.remove()
        browser?.let { session.detachSurface(surfaceId, it); it.destroy() }
    } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        WebView(context).apply {
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
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    session.close()
                    script?.remove()
                    script = null
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    browser = null
                    onRendererGone()
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == originHost) return false
                    if (request.isForMainFrame && request.hasGesture()) session.requestExternal(request.url.toString())
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != originHost) return null
                    return runCatching {
                        val segments = request.url.pathSegments
                        require(segments.size >= 3 && segments.first() == "extension") { "资源路径无效" }
                        val version = segments[1]
                        val path = segments.drop(2).joinToString("/")
                        val mime = when {
                            path.endsWith(".mjs") || path.endsWith(".js") -> "text/javascript"
                            path.endsWith(".wasm") -> "application/wasm"
                            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.')) ?: "application/octet-stream"
                        }
                        val bytes = viewModel.gatewayApi.extensionResource(serviceId, version, path)
                        WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf("Cache-Control" to "private, max-age=31536000, immutable"), bytes.inputStream())
                    }.getOrElse { WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))) }
                }
            }
            val bridgeSource = context.assets.open("bridge.js").bufferedReader().use { it.readText() }
            val themeCss = context.assets.open("theme.css").bufferedReader().use { it.readText() }
            session.attachSurface(surfaceId, this) { origin, html, documentId ->
                confirmation.cancelSurface(surfaceId)
                originHost = Uri.parse(origin).host
                script?.remove()
                val config = JSONObject().put("documentId", documentId).put("theme", currentTheme.id)
                    .put("fontScale", currentScale).put("themeCss", themeCss)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    script = WebViewCompat.addDocumentStartJavaScript(this,
                        "window.__codexExtensionConfig = $config;\n$bridgeSource", setOf(origin))
                    // webview.html belongs to the official extension. Do not rewrite or inject into it.
                    loadDataWithBaseURL("${origin.trimEnd('/')}/", html, "text/html", "UTF-8", null)
                } else onStatus("当前 Android System WebView 不支持页面初始化，请更新系统 WebView 后重试。")
            }
        }
    }, update = { webView ->
        if (!visible && webView.hasFocus()) webView.clearFocus()
        webView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        webView.setBackgroundColor(background)
        webView.evaluateJavascript("window.__codexExtensionHost?.setTheme(${JSONObject.quote(theme.id)}, $effectiveFontScale)", null)
    })
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
