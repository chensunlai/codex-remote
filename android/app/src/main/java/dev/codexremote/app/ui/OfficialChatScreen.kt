@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.codexremote.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.codexremote.app.BuildConfig
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.OfficialChatSession
import dev.codexremote.app.data.OfficialFrontend
import dev.codexremote.app.data.OfficialVersion
import dev.codexremote.app.data.frontendScope
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.objects
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.RuntimeState
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

@Composable
fun OfficialChatScreen(state: AppState, viewModel: MainViewModel) {
    val service = state.services.firstOrNull { it.id == state.selectedServiceId }
    val config = state.gatewayConfig
    if (service == null || config == null || service.runtimeState != RuntimeState.CONNECTED) {
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

    fun install(slot: String, version: OfficialVersion? = null) {
        if (loading) return
        loading = true
        error = null
        frontend = null
        jobs.launch {
            runCatching {
                val selected = version ?: store.versions().firstOrNull { !it.preRelease }
                    ?: error("官方暂无可用稳定版本")
                store.install(slot, selected) { progress -> jobs.launch { status = progress } }
            }.onSuccess {
                store.setMode(scopeKey, slot)
                mode = slot
                frontend = it
                status = null
            }.onFailure { error = it.message; frontend = store.cached(mode) }
            loading = false
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
        TopAppBar(
            title = {
                Column {
                    Text(service.name)
                    Text("${if (mode == "latest") "最新" else "指定版本"} · ${frontend?.version ?: "未下载"}", style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = {
                IconButton(onClick = { viewModel.setSection(MainSection.SERVICES) }) {
                    Icon(CodexIcons.ArrowBack, contentDescription = "切换服务器")
                }
            },
            actions = {
                IconButton(onClick = { chooseWorkspace = true }, enabled = !loading) {
                    Icon(CodexIcons.FolderOpen, contentDescription = "选择工作目录")
                }
                Box {
                    TextButton(onClick = { chooseMode = true }, enabled = !loading) { Text("前端版本") }
                    DropdownMenu(expanded = chooseMode, onDismissRequest = { chooseMode = false }) {
                        DropdownMenuItem(text = { Text("最新") }, onClick = { chooseMode = false; selectSlot("latest") })
                        DropdownMenuItem(text = { Text("指定版本") }, onClick = { chooseMode = false; selectSlot("specified") })
                        DropdownMenuItem(text = { Text("检查并下载最新稳定版") }, onClick = { chooseMode = false; install("latest") })
                        DropdownMenuItem(text = { Text("更换指定版本") }, onClick = { chooseMode = false; chooseSpecifiedVersion() })
                    }
                }
                IconButton(onClick = { viewModel.setSection(MainSection.SETTINGS) }) {
                    Icon(CodexIcons.Settings, contentDescription = "服务器设置")
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                frontend?.let { bundle ->
                    key(scopeKey, bundle.version, bundle.slot, workspace) {
                        OfficialWebView(bundle, scopeKey, store.clientId(scopeKey), service.id,
                            service.home ?: "/", workspace, service.name, viewModel, { status = it }, Modifier.weight(1f))
                    }
                } ?: Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("选择官方前端版本后打开会话。")
                    TextButton(onClick = { install("latest") }) { Text("下载最新稳定版") }
                }
            }
        }
    }

    if (chooseWorkspace) RemoteDirectoryDialog(viewModel.gatewayApi, service.id, workspace,
        onSelect = { path -> store.setWorkspace(scopeKey, path); workspace = path; chooseWorkspace = false },
        onDismiss = { chooseWorkspace = false })

    choices?.let { versions ->
        AlertDialog(
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
    viewModel: MainViewModel,
    onStatus: (String?) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    val session = remember { OfficialChatSession(context.applicationContext, viewModel.gatewayApi, serviceId, clientId,
        home, workspace, serviceName, onStatus, { path, content -> preview = path to content }) }
    val pageId = remember { UUID.randomUUID().toString() }
    val originHost = "s$scope.codex-remote.invalid"
    var browser by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(session) {
        viewModel.officialFrontends.retain(frontend)
        onDispose {
            session.close()
            browser?.destroy()
            browser = null
            viewModel.officialFrontends.release(frontend)
        }
    }
    AndroidView(modifier = modifier.fillMaxWidth(), factory = {
        WebView(context).apply {
            browser = this
            setBackgroundColor(Color.rgb(20, 20, 20))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            addJavascriptInterface(session.bridge, "CodexRemoteHost")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) android.util.Log.d("OfficialCodex", "${message.messageLevel()}: ${message.message().take(1000)}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
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
                                    .replace("<body>", "<body class=\"vscode-dark\">").toByteArray())
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
    })
    preview?.let { (path, content) ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(path.substringAfterLast('/')) },
            text = { SelectionContainer { Text(content, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), style = MonoTextStyle) } },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("关闭") } })
    }
}

@Composable
private fun RemoteDirectoryDialog(api: GatewayApi, serviceId: String, initialPath: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("远端工作目录") }, text = {
        Column {
            OutlinedTextField(input, { input = it }, label = { Text("绝对路径") }, singleLine = true)
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
