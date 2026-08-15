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
import dev.codexremote.app.data.parseChatItem
import dev.codexremote.app.data.parseFiles
import dev.codexremote.app.data.parseModels
import dev.codexremote.app.data.parsePending
import dev.codexremote.app.data.parsePlanSteps
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.data.parseSession
import dev.codexremote.app.data.parseSessions
import dev.codexremote.app.data.parseSkills
import dev.codexremote.app.data.parseThread
import dev.codexremote.app.data.strings
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.NewSessionOptions
import dev.codexremote.app.model.PromptContext
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
    private var sessionSearchJob: Job? = null
    private var cacheScope = ""
    private var lastEventSequence = 0L
    private var currentEventServiceId: String? = null
    private var eventGeneration = 0L
    private var activeOperations = 0
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
        activeOperations = 0
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
                        skills = emptyList(),
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
                skills = emptyList(),
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

    fun setSessionSearch(value: String) {
        _state.update { it.copy(sessionSearch = value) }
        val serviceId = _state.value.selectedServiceId ?: return
        sessionSearchJob?.cancel()
        sessionSearchJob = viewModelScope.launch {
            delay(300)
            runCatching { loadSessions(serviceId) }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: error.toString()) }
                }
        }
    }

    fun showArchivedSessions(value: Boolean) {
        val serviceId = _state.value.selectedServiceId ?: return
        _state.update {
            it.copy(
                showingArchivedSessions = value,
                selectedThreadId = null,
                thread = null,
            )
        }
        launchOperation(if (value) "正在载入归档" else "正在载入会话") {
            loadSessions(serviceId)
        }
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
            loadSkills(serviceId, options.cwd)
            onComplete()
        }
    }

    fun selectSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        val resume = !_state.value.showingArchivedSessions
        _state.update { it.copy(selectedThreadId = threadId, thread = null) }
        launchOperation("正在载入会话") {
            loadThread(serviceId, threadId, resume = resume, allowCache = true)
        }
    }

    fun closeSession() {
        _state.update { it.copy(selectedThreadId = null, thread = null) }
    }

    fun sendMessage(
        text: String,
        model: String?,
        effort: String?,
        context: List<PromptContext> = emptyList(),
        onAccepted: () -> Unit = {},
    ) {
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
                api.steer(serviceId, threadId, activeTurnId, text, context)
            } else {
                val result = api.sendTurn(serviceId, threadId, text, model, effort, context)
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

    fun unarchiveSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在恢复会话") {
            api.unarchiveSession(serviceId, threadId)
            loadSessions(serviceId)
        }
    }

    fun renameSession(threadId: String, name: String, onComplete: () -> Unit = {}) {
        if (name.isBlank()) return
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在重命名会话") {
            api.renameSession(serviceId, threadId, name.trim())
            _state.update { state ->
                state.copy(
                    sessions = state.sessions.map { session ->
                        if (session.id == threadId) session.copy(name = name.trim()) else session
                    },
                    thread = state.thread?.takeIf { it.id == threadId }?.copy(name = name.trim())
                        ?: state.thread,
                )
            }
            onComplete()
        }
    }

    fun compactSession() {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        launchOperation("正在压缩上下文") {
            api.compactSession(serviceId, threadId)
        }
    }

    fun reviewUncommitted() {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        launchOperation("正在开始代码审阅") {
            val result = api.reviewUncommitted(serviceId, threadId)
            val turnId = result.optJSONObject("turn")?.optString("id")
            if (!turnId.isNullOrBlank()) {
                _state.update { state ->
                    state.copy(thread = state.thread?.copy(activeTurnId = turnId))
                }
            }
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

    fun respondToInput(requestId: String, answers: Map<String, List<String>>) {
        val payload = JSONObject()
        answers.forEach { (questionId, values) ->
            payload.put(
                questionId,
                JSONObject().put("answers", org.json.JSONArray(values)),
            )
        }
        launchOperation("正在提交回答") {
            api.respond(requestId, JSONObject().put("answers", payload))
            loadPending(_state.value.selectedServiceId)
        }
    }

    fun respondToPermission(requestId: String, permissionsJson: String, session: Boolean) {
        val requested = runCatching { JSONObject(permissionsJson) }.getOrElse { JSONObject() }
        val permissions = JSONObject().apply {
            requested.optJSONObject("network")?.let { put("network", it) }
            requested.optJSONObject("fileSystem")?.let { put("fileSystem", it) }
        }
        launchOperation("正在提交权限审批") {
            api.respond(
                requestId,
                JSONObject()
                    .put("permissions", permissions)
                    .put("scope", if (session) "session" else "turn"),
            )
            loadPending(_state.value.selectedServiceId)
        }
    }

    fun respondToElicitation(requestId: String, action: String, content: JSONObject? = null) {
        launchOperation("正在提交工具确认") {
            api.respond(
                requestId,
                JSONObject()
                    .put("action", action)
                    .put("content", content ?: JSONObject.NULL)
                    .put("_meta", JSONObject.NULL),
            )
            loadPending(_state.value.selectedServiceId)
        }
    }

    override fun onCleared() {
        events?.close(1000, "Android client closed")
        sessionSearchJob?.cancel()
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
        } else {
            _state.update { it.copy(section = MainSection.SERVICES) }
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
        runCatching { loadSkills(serviceId, path) }
        browseNow(serviceId, path)
        loadServices()
    }

    private suspend fun loadSessions(serviceId: String) {
        val current = _state.value
        val archived = current.showingArchivedSessions
        val searchTerm = current.sessionSearch
        val sessions = parseSessions(
            api.sessions(
                serviceId = serviceId,
                archived = archived,
                searchTerm = searchTerm,
            ),
        )
        val latest = _state.value
        if (
            latest.selectedServiceId == serviceId &&
            latest.showingArchivedSessions == archived &&
            latest.sessionSearch == searchTerm
        ) {
            _state.update { it.copy(sessions = sessions) }
        }
    }

    private suspend fun loadSkills(serviceId: String, cwd: String) {
        val skills = parseSkills(api.skills(serviceId, cwd))
        val current = _state.value
        if (
            current.selectedServiceId == serviceId &&
            (current.thread == null || current.thread.cwd == cwd)
        ) {
            _state.update { it.copy(skills = skills.filter { skill -> skill.enabled }) }
        }
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
        val detail = parseThread(root)
        if (
            _state.value.selectedServiceId != serviceId ||
            _state.value.selectedThreadId != threadId
        ) return
        _state.update { it.copy(thread = detail) }
        detail.cwd?.let { cwd -> runCatching { loadSkills(serviceId, cwd) } }
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
            "codex.item/started", "codex.item/completed" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                val turnId = payload.optString("turnId")
                val item = payload.optJSONObject("item") ?: return
                val turnStatus = if (type.endsWith("completed")) "completed" else "inProgress"
                parseChatItem(turnId, turnStatus, 0, item)?.let { message ->
                    upsertMessage(threadId, turnId, message)
                }
            }
            "codex.item/agentMessage/delta" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                mutateMessage(
                    threadId = threadId,
                    turnId = payload.optString("turnId"),
                    itemId = payload.optString("itemId"),
                    fallback = ChatMessage(
                        payload.optString("itemId"),
                        MessageRole.ASSISTANT,
                        "",
                        "inProgress",
                        "agentMessage",
                    ),
                ) { it.copy(text = it.text + payload.optString("delta")) }
            }
            "codex.item/plan/delta" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                mutateMessage(
                    threadId,
                    payload.optString("turnId"),
                    payload.optString("itemId"),
                    ChatMessage(
                        payload.optString("itemId"),
                        MessageRole.SYSTEM,
                        "",
                        "inProgress",
                        "plan",
                        title = "计划",
                    ),
                ) { it.copy(text = it.text + payload.optString("delta")) }
            }
            "codex.item/reasoning/summaryTextDelta" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                mutateReasoning(payload, detail = false)
            }
            "codex.item/reasoning/textDelta" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                mutateReasoning(payload, detail = true)
            }
            "codex.item/commandExecution/outputDelta" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                mutateMessage(
                    threadId,
                    payload.optString("turnId"),
                    payload.optString("itemId"),
                    ChatMessage(
                        payload.optString("itemId"),
                        MessageRole.TOOL,
                        "",
                        "inProgress",
                        "commandExecution",
                        title = "命令",
                    ),
                ) { message ->
                    message.copy(detail = message.detail.orEmpty() + payload.optString("delta"))
                }
            }
            "codex.item/fileChange/patchUpdated" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                val item = JSONObject()
                    .put("type", "fileChange")
                    .put("id", payload.optString("itemId"))
                    .put("status", "inProgress")
                    .put("changes", payload.optJSONArray("changes"))
                parseChatItem(payload.optString("turnId"), "inProgress", 0, item)?.let { message ->
                    upsertMessage(threadId, payload.optString("turnId"), message)
                }
            }
            "codex.turn/diff/updated" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                updateThread(threadId) { it.copy(latestDiff = payload.optString("diff")) }
            }
            "codex.turn/plan/updated" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                updateThread(threadId) {
                    it.copy(
                        planExplanation = payload.optString("explanation").takeIf(String::isNotBlank),
                        plan = parsePlanSteps(payload.optJSONArray("plan")),
                    )
                }
            }
            "codex.thread/status/changed" -> {
                val threadId = payload.optString("threadId")
                val status = payload.optJSONObject("status") ?: JSONObject()
                val statusType = status.optString("type", "notLoaded")
                _state.update { state ->
                    state.copy(
                        sessions = state.sessions.map { session ->
                            if (session.id == threadId) session.copy(status = statusType) else session
                        },
                        thread = state.thread?.takeIf { it.id == threadId }?.copy(
                            status = statusType,
                            activeFlags = status.optJSONArray("activeFlags")?.strings()?.toSet().orEmpty(),
                        ) ?: state.thread,
                    )
                }
            }
            "codex.thread/name/updated" -> {
                val threadId = payload.optString("threadId")
                val name = payload.optString("threadName").takeIf(String::isNotBlank)
                _state.update { state ->
                    state.copy(
                        sessions = state.sessions.map { session ->
                            if (session.id == threadId) session.copy(name = name) else session
                        },
                        thread = state.thread?.takeIf { it.id == threadId }?.copy(name = name)
                            ?: state.thread,
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
                if (threadId != null && payload.optString("threadId") == threadId) {
                    if (type == "codex.error") {
                        val error = payload.optJSONObject("error")?.optString("message")
                            ?: payload.optString("message", "Codex 执行失败")
                        upsertMessage(
                            threadId,
                            payload.optString("turnId"),
                            ChatMessage(
                                id = "${payload.optString("turnId")}-error",
                                role = MessageRole.SYSTEM,
                                text = error,
                                status = "failed",
                                kind = "error",
                                title = "执行失败",
                            ),
                            markActive = false,
                        )
                    }
                    updateThread(threadId) { it.copy(activeTurnId = null) }
                }
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
            "codex.thread/archived", "codex.thread/unarchived", "codex.thread/deleted" -> {
                val serviceId = _state.value.selectedServiceId ?: return
                viewModelScope.launch { runCatching { loadSessions(serviceId) } }
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

    private fun mutateReasoning(payload: JSONObject, detail: Boolean) {
        val itemId = payload.optString("itemId")
        mutateMessage(
            threadId = payload.optString("threadId"),
            turnId = payload.optString("turnId"),
            itemId = itemId,
            fallback = ChatMessage(
                id = itemId,
                role = MessageRole.SYSTEM,
                text = if (detail) "正在分析" else "",
                status = "inProgress",
                kind = "reasoning",
                title = "分析",
            ),
        ) { message ->
            if (detail) {
                message.copy(detail = message.detail.orEmpty() + payload.optString("delta"))
            } else {
                message.copy(text = message.text + payload.optString("delta"))
            }
        }
    }

    private fun mutateMessage(
        threadId: String,
        turnId: String,
        itemId: String,
        fallback: ChatMessage,
        transform: (ChatMessage) -> ChatMessage,
    ) {
        _state.update { state ->
            val thread = state.thread?.takeIf { it.id == threadId } ?: return@update state
            val index = thread.messages.indexOfLast { it.id == itemId }
            val messages = if (index >= 0) {
                thread.messages.toMutableList().also { list ->
                    list[index] = transform(list[index])
                }
            } else {
                thread.messages + transform(fallback)
            }
            state.copy(
                thread = thread.copy(
                    messages = messages,
                    activeTurnId = turnId.takeIf(String::isNotBlank) ?: thread.activeTurnId,
                ),
            )
        }
    }

    private fun upsertMessage(
        threadId: String,
        turnId: String,
        message: ChatMessage,
        markActive: Boolean = true,
    ) {
        _state.update { state ->
            val thread = state.thread?.takeIf { it.id == threadId } ?: return@update state
            var current = thread.messages
            if (message.role == MessageRole.USER) {
                current = current.filterNot {
                    it.id.startsWith("local-") && it.role == MessageRole.USER && it.text == message.text
                }
            }
            val index = current.indexOfLast { it.id == message.id }
            val messages = if (index >= 0) {
                current.toMutableList().also { it[index] = message }
            } else {
                current + message
            }
            state.copy(
                thread = thread.copy(
                    messages = messages,
                    activeTurnId = if (markActive && turnId.isNotBlank()) turnId else thread.activeTurnId,
                ),
            )
        }
    }

    private fun updateThread(threadId: String, transform: (dev.codexremote.app.model.ThreadDetail) -> dev.codexremote.app.model.ThreadDetail) {
        _state.update { state ->
            val thread = state.thread?.takeIf { it.id == threadId } ?: return@update state
            state.copy(thread = transform(thread))
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
            activeOperations += 1
            _state.update { it.copy(loading = true, operation = label, error = null) }
            try {
                block()
            } catch (error: Throwable) {
                _state.update { it.copy(error = error.message ?: error.toString()) }
            } finally {
                activeOperations = (activeOperations - 1).coerceAtLeast(0)
                _state.update {
                    it.copy(
                        loading = activeOperations > 0,
                        operation = if (activeOperations > 0) it.operation else null,
                    )
                }
            }
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
