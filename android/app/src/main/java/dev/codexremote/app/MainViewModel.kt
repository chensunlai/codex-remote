package dev.codexremote.app

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexremote.app.data.ChatCache
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.SecretStore
import dev.codexremote.app.data.objects
import dev.codexremote.app.data.parseFiles
import dev.codexremote.app.data.parseModels
import dev.codexremote.app.data.parsePending
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.data.parseSession
import dev.codexremote.app.data.parseSessions
import dev.codexremote.app.data.parseThread
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.NewSessionOptions
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.FileNotFoundException
import java.net.URI

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val secretStore = SecretStore(application)
    private val chatCache = ChatCache(application)
    private val eventPreferences = application.getSharedPreferences("event_offsets", 0)
    private val api = GatewayApi()
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var events: WebSocket? = null
    private var reconnectJob: Job? = null
    private var cacheScope = ""
    private var lastEventSequence = 0L
    private var currentEventServiceId: String? = null
    private var eventGeneration = 0L
    private val subscribedThreads = mutableSetOf<String>()

    init {
        secretStore.load()?.let { config ->
            activateConfig(config)
            _state.update { it.copy(configured = true, gatewayConfig = config) }
            launchOperation("正在连接 Gateway") {
                loadMeta()
                loadServicesAndSelect()
            }
        }
    }

    fun configureGateway(address: String, token: String) {
        launchOperation("正在连接 Gateway") {
            val config = GatewayConfig(normalizeGatewayUrl(address), token.trim())
            require(config.token.isNotBlank()) { "访问令牌不能为空" }
            api.configure(config)
            val meta = api.meta()
            secretStore.save(config)
            activateConfig(config)
            _state.update { it.copy(configured = true, gatewayConfig = config, error = null) }
            applyMeta(meta)
            loadServicesAndSelect()
        }
    }

    fun clearGateway() {
        events?.cancel()
        events = null
        reconnectJob?.cancel()
        eventGeneration += 1
        api.clear()
        secretStore.clear()
        cacheScope = ""
        lastEventSequence = 0
        currentEventServiceId = null
        subscribedThreads.clear()
        _state.value = AppState()
    }

    fun setSection(section: MainSection) {
        _state.update { it.copy(section = section) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun refreshServices() {
        launchOperation("正在刷新服务") { loadServicesAndSelect() }
    }

    fun deleteService(serviceId: String) {
        launchOperation("正在删除服务") {
            api.deleteService(serviceId)
            chatCache.delete(cacheScope, serviceId)
            subscribedThreads.removeAll { it.startsWith("$serviceId:") }
            if (_state.value.selectedServiceId == serviceId) {
                events?.cancel()
                events = null
                _state.update {
                    it.copy(
                        selectedServiceId = null,
                        sessions = emptyList(),
                        models = emptyList(),
                        remoteFiles = emptyList(),
                        selectedThreadId = null,
                        thread = null,
                    )
                }
            }
            loadServicesAndSelect()
        }
    }

    fun testService(serviceId: String) {
        launchOperation("正在测试 Agent 与 Codex") {
            api.testService(serviceId)
            loadServices()
        }
    }

    fun selectService(serviceId: String) {
        if (_state.value.selectedServiceId == serviceId && _state.value.models.isNotEmpty()) return
        _state.update {
            it.copy(
                selectedServiceId = serviceId,
                sessions = emptyList(),
                models = emptyList(),
                selectedThreadId = null,
                thread = null,
                remoteFiles = emptyList(),
                remotePath = "",
                filePreview = null,
            )
        }
        connectEvents(serviceId)
        launchOperation("正在载入服务") { loadServiceWorkspace(serviceId) }
    }

    fun refreshSessions() {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在刷新会话") { loadSessions(serviceId) }
    }

    fun createSession(options: NewSessionOptions, onComplete: () -> Unit = {}) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在创建会话") {
            val result = api.createSession(serviceId, options)
            val thread = result.optJSONObject("thread")
                ?: error("Codex 未返回会话")
            val threadId = thread.optString("id")
            require(threadId.isNotBlank()) { "Codex 未返回会话 ID" }
            subscribedThreads.add(threadKey(serviceId, threadId))
            val summary = parseSession(thread)
            val detail = parseThread(result)
            _state.update { state ->
                state.copy(
                    sessions = listOf(summary) + state.sessions.filterNot { it.id == threadId },
                    selectedThreadId = threadId,
                    thread = detail,
                    section = MainSection.SESSIONS,
                )
            }
            onComplete()
        }
    }

    fun selectSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        _state.update { it.copy(selectedThreadId = threadId, thread = null) }
        launchOperation("正在载入会话") {
            loadThread(serviceId, threadId, resume = true, allowCache = true)
        }
    }

    fun closeSession() {
        _state.update { it.copy(selectedThreadId = null, thread = null) }
    }

    fun sendMessage(text: String, model: String?, effort: String?, onAccepted: () -> Unit = {}) {
        if (text.isBlank()) return
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        launchOperation("正在发送") {
            if (!subscribedThreads.contains(threadKey(serviceId, threadId))) {
                val resumed = api.resume(serviceId, threadId)
                val summary = _state.value.sessions.firstOrNull { it.id == threadId }
                chatCache.put(cacheScope, serviceId, threadId, summary?.updatedAt ?: 0, resumed)
                _state.update { it.copy(thread = parseThread(resumed)) }
                subscribedThreads.add(threadKey(serviceId, threadId))
            }
            val activeTurnId = _state.value.thread?.activeTurnId
            if (activeTurnId != null) {
                api.steer(serviceId, threadId, activeTurnId, text)
            } else {
                val result = api.sendTurn(serviceId, threadId, text, model, effort)
                val turnId = result.optJSONObject("turn")?.optString("id")
                val optimistic = ChatMessage(
                    id = "local-" + System.nanoTime(),
                    role = MessageRole.USER,
                    text = text,
                    status = "inProgress",
                )
                _state.update { state ->
                    state.copy(
                        thread = state.thread?.copy(
                            messages = state.thread.messages + optimistic,
                            activeTurnId = turnId,
                        ),
                    )
                }
            }
            onAccepted()
        }
    }

    fun interruptTurn() {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        val turnId = current.thread?.activeTurnId ?: return
        launchOperation("正在停止") { api.interrupt(serviceId, threadId, turnId) }
    }

    fun archiveSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在归档会话") {
            api.archiveSession(serviceId, threadId)
            chatCache.delete(cacheScope, serviceId, threadId)
            if (_state.value.selectedThreadId == threadId) closeSession()
            loadSessions(serviceId)
        }
    }

    fun deleteSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在删除会话") {
            api.deleteSession(serviceId, threadId)
            chatCache.delete(cacheScope, serviceId, threadId)
            if (_state.value.selectedThreadId == threadId) closeSession()
            loadSessions(serviceId)
        }
    }

    fun browse(path: String? = null) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在读取目录") {
            val target = path ?: _state.value.remotePath.ifBlank { api.home(serviceId) }
            val list = parseFiles(api.files(serviceId, target))
            _state.update { it.copy(remotePath = target, remoteFiles = list, filePreview = null) }
        }
    }

    fun createDirectory(name: String, onComplete: () -> Unit = {}) {
        val serviceId = _state.value.selectedServiceId ?: return
        val currentPath = _state.value.remotePath
        launchOperation("正在创建目录") {
            api.mkdir(serviceId, joinRemote(currentPath, name))
            browseNow(serviceId, currentPath)
            onComplete()
        }
    }

    fun deleteRemote(file: RemoteFile) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在删除 " + file.name) {
            api.deleteFile(serviceId, file.path, file.type == RemoteFileType.DIRECTORY)
            browseNow(serviceId, _state.value.remotePath)
        }
    }

    fun upload(resolver: ContentResolver, uri: Uri, remoteName: String? = null) {
        val serviceId = _state.value.selectedServiceId ?: return
        val metadata = resolver.metadata(uri)
        val name = remoteName?.takeIf(String::isNotBlank) ?: metadata.first
        val remotePath = joinRemote(_state.value.remotePath, name)
        launchOperation("正在上传 " + name) {
            api.upload(
                serviceId = serviceId,
                path = remotePath,
                fileName = name,
                contentType = resolver.getType(uri),
                contentLength = metadata.second,
                open = {
                    resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                },
            )
            browseNow(serviceId, _state.value.remotePath)
        }
    }

    fun download(resolver: ContentResolver, file: RemoteFile, destination: Uri) {
        val serviceId = _state.value.selectedServiceId ?: return
        if (file.size > _state.value.maxDownloadBytes) {
            _state.update {
                it.copy(error = "文件超过 Gateway 下载限制 " + formatBytes(it.maxDownloadBytes))
            }
            return
        }
        launchOperation("正在下载 " + file.name) {
            val output = resolver.openOutputStream(destination)
                ?: throw FileNotFoundException(destination.toString())
            output.use { api.download(serviceId, file.path, it) }
        }
    }

    fun reportDownloadLimit(file: RemoteFile) {
        _state.update {
            it.copy(
                error = "${file.name} 超过 Gateway 下载限制 ${formatBytes(it.maxDownloadBytes)}",
            )
        }
    }

    fun preview(file: RemoteFile) {
        val serviceId = _state.value.selectedServiceId ?: return
        if (file.size > _state.value.maxDownloadBytes) {
            reportDownloadLimit(file)
            return
        }
        launchOperation("正在预览 " + file.name) {
            val payload = api.preview(serviceId, file.path)
            _state.update {
                it.copy(
                    filePreview = FilePreview(
                        name = file.name,
                        path = file.path,
                        content = payload.content,
                        truncated = payload.truncated,
                    ),
                )
            }
        }
    }

    fun closePreview() {
        _state.update { it.copy(filePreview = null) }
    }

    fun openTerminal(
        cols: Int,
        rows: Int,
        cwd: String?,
        listener: WebSocketListener,
    ): WebSocket? {
        val serviceId = _state.value.selectedServiceId ?: return null
        return api.terminal(serviceId, cols, rows, cwd, listener)
    }

    fun respondToRequest(requestId: String, decision: String) {
        launchOperation("正在提交审批") {
            api.respond(requestId, JSONObject().put("decision", decision))
            loadPending(_state.value.selectedServiceId)
        }
    }

    override fun onCleared() {
        events?.close(1000, "Android client closed")
        chatCache.close()
    }

    private fun activateConfig(config: GatewayConfig) {
        api.configure(config)
        cacheScope = ChatCache.scope(config)
        lastEventSequence = 0
        currentEventServiceId = null
    }

    private suspend fun loadMeta() {
        applyMeta(api.meta())
    }

    private fun applyMeta(meta: JSONObject) {
        val maxDownload = meta.optJSONObject("limits")?.optLong("downloadBytes")
            ?.takeIf { it > 0 }
            ?: 10L * 1024 * 1024
        _state.update { it.copy(maxDownloadBytes = maxDownload) }
    }

    private suspend fun loadServicesAndSelect() {
        loadServices()
        val selected = _state.value.selectedServiceId
            ?.takeIf { id -> _state.value.services.any { it.id == id } }
            ?: _state.value.services.firstOrNull { it.runtimeState == RuntimeState.CONNECTED }?.id
            ?: _state.value.services.firstOrNull()?.id
        if (selected != null) {
            _state.update { it.copy(selectedServiceId = selected) }
            connectEvents(selected)
            if (_state.value.services.firstOrNull { it.id == selected }?.runtimeState ==
                RuntimeState.CONNECTED
            ) {
                loadServiceWorkspace(selected)
            }
        }
    }

    private suspend fun loadServices() {
        val services = parseServices(api.services())
        val selected = _state.value.selectedServiceId
        _state.update {
            it.copy(
                services = services,
                eventConnected = selected != null &&
                    services.firstOrNull { service -> service.id == selected }?.runtimeState ==
                    RuntimeState.CONNECTED &&
                    it.eventConnected,
            )
        }
    }

    private suspend fun loadServiceWorkspace(serviceId: String) {
        val models = parseModels(api.models(serviceId))
        _state.update { it.copy(models = models) }
        loadSessions(serviceId)
        loadPending(serviceId)
        val path = _state.value.services.firstOrNull { it.id == serviceId }?.home
            ?: api.home(serviceId)
        browseNow(serviceId, path)
        loadServices()
    }

    private suspend fun loadSessions(serviceId: String) {
        _state.update { it.copy(sessions = parseSessions(api.sessions(serviceId))) }
    }

    private suspend fun loadThread(
        serviceId: String,
        threadId: String,
        resume: Boolean,
        allowCache: Boolean,
    ) {
        val summary = _state.value.sessions.firstOrNull { it.id == threadId }
        val cached = if (allowCache && summary?.status != "active") {
            chatCache.get(cacheScope, serviceId, threadId, summary?.updatedAt ?: 0)
        } else null
        val root = cached ?: (if (resume) api.resume(serviceId, threadId) else api.thread(serviceId, threadId)).also {
            chatCache.put(cacheScope, serviceId, threadId, summary?.updatedAt ?: 0, it)
        }
        if (cached == null && resume) subscribedThreads.add(threadKey(serviceId, threadId))
        _state.update { it.copy(thread = parseThread(root)) }
    }

    private suspend fun loadPending(serviceId: String?) {
        _state.update { it.copy(pendingRequests = parsePending(api.pendingRequests(serviceId))) }
    }

    private suspend fun browseNow(serviceId: String, path: String) {
        _state.update {
            it.copy(remotePath = path, remoteFiles = parseFiles(api.files(serviceId, path)))
        }
    }

    private fun connectEvents(serviceId: String) {
        events?.cancel()
        reconnectJob?.cancel()
        val generation = ++eventGeneration
        currentEventServiceId = serviceId
        lastEventSequence = eventPreferences.getLong(eventOffsetKey(cacheScope, serviceId), 0)
        _state.update { it.copy(eventConnected = false) }
        events = api.events(lastEventSequence, serviceId, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != eventGeneration) return
                _state.update { it.copy(eventConnected = true) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != eventGeneration) return
                runCatching { JSONObject(text) }.onSuccess(::handleEvent)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation != eventGeneration) return
                _state.update { it.copy(eventConnected = false) }
                scheduleReconnect(serviceId, generation)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != eventGeneration) return
                _state.update { it.copy(eventConnected = false) }
                scheduleReconnect(serviceId, generation)
            }
        })
    }

    private fun scheduleReconnect(serviceId: String, generation: Long) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(2_500)
            if (
                generation == eventGeneration &&
                _state.value.configured &&
                _state.value.selectedServiceId == serviceId
            ) {
                connectEvents(serviceId)
            }
        }
    }

    private fun handleEvent(event: JSONObject) {
        if (event.optString("type") == "gateway.replay") {
            val payload = event.optJSONObject("payload")
            payload?.optJSONArray("events")?.objects()?.forEach(::handleEvent)
            updateEventSequence(payload?.optLong("latestSequence") ?: 0)
            return
        }
        updateEventSequence(event.optLong("sequence"))
        val type = event.optString("type")
        val payload = event.optJSONObject("payload") ?: JSONObject()
        val selectedThread = _state.value.selectedThreadId
        when (type) {
            "codex.item/agentMessage/delta" -> {
                if (payload.optString("threadId") != selectedThread) return
                val itemId = payload.optString("itemId")
                val delta = payload.optString("delta")
                _state.update { state ->
                    val thread = state.thread ?: return@update state
                    val index = thread.messages.indexOfLast { it.id == itemId }
                    val messages = if (index >= 0) {
                        thread.messages.toMutableList().also { list ->
                            list[index] = list[index].copy(text = list[index].text + delta)
                        }
                    } else {
                        thread.messages + ChatMessage(
                            itemId,
                            MessageRole.ASSISTANT,
                            delta,
                            "inProgress",
                        )
                    }
                    state.copy(
                        thread = thread.copy(
                            messages = messages,
                            activeTurnId = payload.optString("turnId"),
                        ),
                    )
                }
            }
            "codex.turn/started" -> {
                if (payload.optString("threadId") != selectedThread) return
                val turnId = payload.optJSONObject("turn")?.optString("id")
                    ?: payload.optString("turnId")
                _state.update { state ->
                    state.copy(thread = state.thread?.copy(activeTurnId = turnId))
                }
            }
            "codex.turn/completed", "codex.error" -> {
                val serviceId = _state.value.selectedServiceId ?: return
                val threadId = selectedThread
                viewModelScope.launch {
                    runCatching { loadSessions(serviceId) }
                    if (threadId != null) {
                        runCatching {
                            loadThread(serviceId, threadId, resume = false, allowCache = false)
                        }
                    }
                    runCatching { loadPending(serviceId) }
                }
            }
            "codex.request", "codex.request.resolved" -> {
                viewModelScope.launch {
                    runCatching { loadPending(_state.value.selectedServiceId) }
                }
            }
            "service.status", "service.updated" -> {
                if (
                    type == "service.status" &&
                    payload.optString("state") == "disconnected"
                ) {
                    event.optString("serviceId").takeIf(String::isNotBlank)?.let { serviceId ->
                        subscribedThreads.removeAll { it.startsWith("$serviceId:") }
                    }
                }
                viewModelScope.launch { runCatching { loadServices() } }
            }
        }
    }

    private fun updateEventSequence(sequence: Long) {
        if (sequence <= lastEventSequence) return
        lastEventSequence = sequence
        val serviceId = currentEventServiceId ?: return
        if (cacheScope.isNotBlank()) {
            eventPreferences.edit {
                putLong(eventOffsetKey(cacheScope, serviceId), lastEventSequence)
            }
        }
    }

    private fun launchOperation(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, operation = label, error = null) }
            runCatching { block() }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: error.toString()) }
                }
            _state.update { it.copy(loading = false, operation = null) }
        }
    }
}

private fun normalizeGatewayUrl(value: String): String {
    val input = value.trim()
    val withScheme = if (input.contains("://")) input else "https://" + input
    val uri = URI(withScheme)
    require(uri.scheme == "https" || uri.scheme == "http") {
        "Gateway 地址需要使用 https:// 或 http://"
    }
    require(!uri.host.isNullOrBlank()) { "Gateway 地址无效" }
    require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Gateway 地址不应包含账号、查询参数或片段"
    }
    return withScheme.trimEnd('/')
}

private fun joinRemote(parent: String, name: String): String =
    if (parent == "/") "/" + name.trim('/') else parent.trimEnd('/') + "/" + name.trim('/')

private fun threadKey(serviceId: String, threadId: String): String = "$serviceId:$threadId"

private fun eventOffsetKey(scope: String, serviceId: String): String = "$scope:$serviceId"

private fun ContentResolver.metadata(uri: Uri): Pair<String, Long> {
    var name = "upload.bin"
    var size = -1L
    val cursor: Cursor? = query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
        }
    }
    return name to size
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index += 1
    }
    return "%.1f %s".format(value, units[index])
}
