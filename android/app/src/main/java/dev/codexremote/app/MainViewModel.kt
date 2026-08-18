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
import dev.codexremote.app.data.GatewayException
import dev.codexremote.app.data.SecretStore
import dev.codexremote.app.data.SessionPreferences
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.data.objects
import dev.codexremote.app.data.parseChatItem
import dev.codexremote.app.data.parseCollaborationModes
import dev.codexremote.app.data.parseFileSearchResults
import dev.codexremote.app.data.parseFiles
import dev.codexremote.app.data.parseModels
import dev.codexremote.app.data.parsePending
import dev.codexremote.app.data.parsePermissionProfiles
import dev.codexremote.app.data.parsePlanSteps
import dev.codexremote.app.data.parseRateLimits
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.data.parseSession
import dev.codexremote.app.data.parseSessions
import dev.codexremote.app.data.parseSkills
import dev.codexremote.app.data.parseThread
import dev.codexremote.app.data.parseThreadGoal
import dev.codexremote.app.data.parseThreadSettings
import dev.codexremote.app.data.parseThreadTokenUsage
import dev.codexremote.app.data.parseTurnSummary
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
import dev.codexremote.app.model.ThreadSettings
import dev.codexremote.app.model.ThreadSettingsUpdate
import dev.codexremote.app.model.ThreadTakeoverPrompt
import dev.codexremote.app.model.TurnSummary
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
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val secretStore = SecretStore(application)
    private val chatCache = ChatCache(application)
    private val sessionPreferences = SessionPreferences(application)
    private val uiPreferences = UiPreferences(application)
    private val eventPreferences = application.getSharedPreferences("event_offsets", 0)
    private val api = GatewayApi()
    private val _state = MutableStateFlow(AppState(fontScale = uiPreferences.fontScale))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var events: WebSocket? = null
    private var reconnectJob: Job? = null
    private var leaseHeartbeatJob: Job? = null
    private var sessionSearchJob: Job? = null
    private var contextFileSearchJob: Job? = null
    private var cacheScope = ""
    private var lastEventSequence = 0L
    private var currentEventServiceId: String? = null
    private var eventGeneration = 0L
    private var activeOperations = 0
    private val subscribedThreads = mutableSetOf<String>()
    private val newEmptyThreads = mutableSetOf<String>()
    private val pendingMessageSubmissions = mutableSetOf<String>()
    private val pendingThreadResumes = mutableSetOf<String>()
    private var viewedThread: ViewedThread? = null
    private var appForeground = false

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
        stopViewingThread()
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
        newEmptyThreads.clear()
        pendingMessageSubmissions.clear()
        pendingThreadResumes.clear()
        _state.value = AppState(fontScale = uiPreferences.fontScale)
    }

    fun setSection(section: MainSection) {
        if (section != MainSection.SESSIONS) {
            stopViewingThread()
            discardSelectedEmptyThread()
        }
        _state.update { it.copy(section = section) }
        if (section == MainSection.SESSIONS) resumeViewingSelectedThread()
    }

    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        if (foreground) resumeViewingSelectedThread() else stopViewingThread()
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun updateFontScale(value: Float) {
        val normalized = uiPreferences.setFontScale(value)
        _state.update { it.copy(fontScale = normalized) }
    }

    fun searchContextFiles(query: String) {
        val normalized = query.trim()
        _state.update {
            it.copy(
                contextFileSearchQuery = query,
                contextFileSearchResults = if (normalized.isEmpty()) {
                    emptyList()
                } else {
                    it.contextFileSearchResults
                },
            )
        }
        contextFileSearchJob?.cancel()
        if (normalized.isEmpty()) return
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val cwd = current.thread?.cwd ?: return
        contextFileSearchJob = viewModelScope.launch {
            delay(200)
            runCatching { parseFileSearchResults(api.searchFiles(serviceId, cwd, normalized)) }
                .onSuccess { results ->
                    if (
                        _state.value.selectedServiceId == serviceId &&
                        _state.value.thread?.cwd == cwd &&
                        _state.value.contextFileSearchQuery.trim() == normalized
                    ) {
                        _state.update { it.copy(contextFileSearchResults = results) }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: error.toString()) }
                }
        }
    }

    fun clearContextFileSearch() {
        contextFileSearchJob?.cancel()
        _state.update {
            it.copy(contextFileSearchQuery = "", contextFileSearchResults = emptyList())
        }
    }

    fun refreshServices() {
        launchOperation("正在刷新服务") { loadServicesAndSelect() }
    }

    fun deleteService(serviceId: String) {
        launchOperation("正在删除服务") {
            api.deleteService(serviceId)
            chatCache.delete(cacheScope, serviceId)
            subscribedThreads.removeAll { it.startsWith("$serviceId:") }
            newEmptyThreads.removeAll { it.startsWith("$serviceId:") }
            if (_state.value.selectedServiceId == serviceId) {
                events?.cancel()
                events = null
                _state.update {
                    it.copy(
                        selectedServiceId = null,
                        sessions = emptyList(),
                        models = emptyList(),
                        collaborationModes = emptyList(),
                        permissionProfiles = emptyList(),
                        skills = emptyList(),
                        remoteFiles = emptyList(),
                        contextFileSearchQuery = "",
                        contextFileSearchResults = emptyList(),
                        rateLimits = null,
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
        stopViewingThread()
        discardSelectedEmptyThread()
        _state.update {
            it.copy(
                selectedServiceId = serviceId,
                sessions = emptyList(),
                models = emptyList(),
                collaborationModes = emptyList(),
                permissionProfiles = emptyList(),
                skills = emptyList(),
                selectedThreadId = null,
                thread = null,
                remoteFiles = emptyList(),
                contextFileSearchQuery = "",
                contextFileSearchResults = emptyList(),
                rateLimits = null,
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
        stopViewingThread()
        discardSelectedEmptyThread()
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

    fun createSession(cwd: String, onComplete: () -> Unit = {}) {
        val serviceId = _state.value.selectedServiceId ?: return
        stopViewingThread()
        discardSelectedEmptyThread()
        launchOperation("正在创建会话") {
            val options = initialSessionOptions(serviceId, cwd)
            val result = api.createSession(serviceId, options)
            val thread = result.optJSONObject("thread")
                ?: error("Codex 未返回会话")
            val threadId = thread.optString("id")
            require(threadId.isNotBlank()) { "Codex 未返回会话 ID" }
            subscribedThreads.add(threadKey(serviceId, threadId))
            val summary = parseSession(thread)
            val parsed = parseThread(result)
            val detail = parsed.copy(
                settings = resolveThreadSettings(result, parsed.settings, options),
            )
            newEmptyThreads.add(threadKey(serviceId, threadId))
            _state.update { state ->
                state.copy(
                    sessions = listOf(summary) + state.sessions.filterNot { it.id == threadId },
                    selectedThreadId = threadId,
                    thread = detail,
                    section = MainSection.SESSIONS,
                )
            }
            rememberThreadSettings(serviceId, detail.settings)
            if (!startViewingThread(serviceId, threadId)) {
                deferThreadRelease(serviceId, threadId)
            }
            loadPermissionProfiles(serviceId, detail.cwd ?: options.cwd)
            loadSkills(serviceId, detail.cwd ?: options.cwd)
            onComplete()
        }
    }

    fun selectSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        if (_state.value.selectedThreadId == threadId && _state.value.thread != null) return
        stopViewingThread()
        discardSelectedEmptyThread()
        val resume = !_state.value.showingArchivedSessions
        _state.update {
            it.copy(
                selectedThreadId = threadId,
                thread = null,
                threadTakeoverPrompt = null,
            )
        }
        launchOperation("正在载入会话") {
            try {
                loadThread(serviceId, threadId, resume = resume, allowCache = true)
            } catch (error: GatewayException) {
                if (!showThreadTakeover(error, serviceId, threadId)) throw error
            }
        }
    }

    fun dismissThreadTakeover() {
        _state.update { state ->
            val clearSelection = state.threadTakeoverPrompt?.clearSelectionOnDismiss == true
            state.copy(
                selectedThreadId = if (clearSelection) null else state.selectedThreadId,
                thread = if (clearSelection) null else state.thread,
                threadTakeoverPrompt = null,
            )
        }
    }

    fun confirmThreadTakeover() {
        val prompt = _state.value.threadTakeoverPrompt ?: return
        _state.update { it.copy(threadTakeoverPrompt = null) }
        launchOperation("正在接管会话") {
            loadThread(
                serviceId = prompt.serviceId,
                threadId = prompt.threadId,
                resume = true,
                allowCache = false,
                takeover = true,
            )
        }
    }

    fun closeSession() {
        stopViewingThread()
        if (!discardSelectedEmptyThread()) clearSelectedThread()
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
        val requestKey = threadKey(serviceId, threadId)
        if (!pendingMessageSubmissions.add(requestKey)) return
        launchOperation("正在发送") {
            try {
                if (!subscribedThreads.contains(requestKey)) {
                    val resumed = try {
                        api.resume(serviceId, threadId)
                    } catch (error: GatewayException) {
                        if (!showThreadTakeover(error, serviceId, threadId)) throw error
                        return@launchOperation
                    }
                    val summary = _state.value.sessions.firstOrNull { it.id == threadId }
                    chatCache.put(cacheScope, serviceId, threadId, summary?.updatedAt ?: 0, resumed)
                    val rawParsed = parseThread(resumed)
                    val parsed = rawParsed.copy(
                        settings = resolveThreadSettings(
                            resumed,
                            rawParsed.settings,
                            _state.value.thread?.settings?.toOptions(),
                        ),
                    )
                    _state.update { state ->
                        state.copy(
                            thread = parsed.copy(
                                tokenUsage = state.thread?.takeIf { it.id == parsed.id }?.tokenUsage,
                                goal = state.thread?.takeIf { it.id == parsed.id }?.goal,
                            ),
                        )
                    }
                    rememberThreadSettings(serviceId, parsed.settings)
                    subscribedThreads.add(requestKey)
                }
                val activeTurnId = _state.value.thread?.activeTurnId
                if (activeTurnId != null) {
                    api.steer(serviceId, threadId, activeTurnId, text, context)
                } else {
                    val result = api.sendTurn(serviceId, threadId, text, model, effort, context)
                    val turnJson = result.optJSONObject("turn")
                    val turnId = turnJson?.optString("id")
                    val turn = turnJson?.let(::parseTurnSummary)
                    val optimistic = ChatMessage(
                        id = "local-" + System.nanoTime(),
                        role = MessageRole.USER,
                        text = text,
                        status = "inProgress",
                        turnId = turnId,
                    )
                    newEmptyThreads.remove(requestKey)
                    _state.update { state ->
                        val selected = state.thread?.takeIf { it.id == threadId }
                        state.copy(
                            sessions = state.sessions.map { session ->
                                if (session.id == threadId && session.preview.isBlank()) {
                                    session.copy(
                                        preview = text,
                                        updatedAt = System.currentTimeMillis() / 1_000,
                                    )
                                } else {
                                    session
                                }
                            },
                            thread = selected?.copy(
                                messages = selected.messages.withOptimisticUserMessage(optimistic),
                                activeTurnId = turnId,
                                turns = turn?.let { selected.turns.upsert(it) }
                                    ?: selected.turns,
                            ) ?: state.thread,
                        )
                    }
                }
                onAccepted()
            } finally {
                pendingMessageSubmissions.remove(requestKey)
            }
        }
    }

    fun interruptTurn() {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        val turnId = current.thread?.activeTurnId ?: return
        launchOperation("正在停止") { api.interrupt(serviceId, threadId, turnId) }
    }

    fun releaseSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        val wasViewing = viewedThread?.serviceId == serviceId && viewedThread?.threadId == threadId
        val stoppedHeartbeat = if (wasViewing) stopViewingThread(notifyGateway = false) else null
        launchOperation("正在释放占用") {
            try {
                stoppedHeartbeat?.join()
                api.releaseSession(serviceId, threadId)
            } catch (error: Throwable) {
                if (
                    wasViewing &&
                    _state.value.selectedServiceId == serviceId &&
                    _state.value.selectedThreadId == threadId
                ) {
                    startViewingThread(serviceId, threadId)
                }
                throw error
            }
            subscribedThreads.remove(threadKey(serviceId, threadId))
            _state.update { state ->
                state.copy(
                    sessions = state.sessions.map { session ->
                        if (session.id == threadId) session.copy(locked = false) else session
                    },
                )
            }
        }
    }

    fun updateThreadSettings(update: ThreadSettingsUpdate) {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        current.thread ?: return
        launchOperation("正在更新会话设置") {
            api.updateThreadSettings(serviceId, threadId, update)
            var appliedSettings: ThreadSettings? = null
            _state.update { state ->
                val selected = state.thread?.takeIf { it.id == threadId } ?: return@update state
                val settings = selected.settings.apply(update)
                appliedSettings = settings
                state.copy(
                    thread = selected.copy(
                        cwd = settings.cwd ?: selected.cwd,
                        settings = settings,
                    ),
                )
            }
            appliedSettings?.let { rememberThreadSettings(serviceId, it) }
            chatCache.delete(cacheScope, serviceId, threadId)
            if (update.cwd != null) {
                loadPermissionProfiles(serviceId, update.cwd)
                runCatching { loadSkills(serviceId, update.cwd) }
            }
        }
    }

    fun setGoal(objective: String, onAccepted: () -> Unit = {}) {
        val value = objective.trim()
        if (value.isBlank()) return
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        launchOperation("正在设置 Goal") {
            val goal = parseThreadGoal(api.setGoal(serviceId, threadId, objective = value).optJSONObject("goal"))
                ?: error("Codex 未返回 Goal")
            updateThread(threadId) { it.copy(goal = goal) }
            onAccepted()
        }
    }

    fun setGoalStatus(status: String) {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        launchOperation("正在更新 Goal") {
            val goal = parseThreadGoal(api.setGoal(serviceId, threadId, status = status).optJSONObject("goal"))
                ?: error("Codex 未返回 Goal")
            updateThread(threadId) { it.copy(goal = goal) }
        }
    }

    fun clearGoal() {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.selectedThreadId ?: return
        launchOperation("正在清除 Goal") {
            api.clearGoal(serviceId, threadId)
            updateThread(threadId) { it.copy(goal = null) }
        }
    }

    fun archiveSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在归档会话") {
            newEmptyThreads.remove(threadKey(serviceId, threadId))
            api.archiveSession(serviceId, threadId)
            chatCache.delete(cacheScope, serviceId, threadId)
            if (_state.value.selectedThreadId == threadId) clearSelectedThread()
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
                newEmptyThreads.remove(threadKey(serviceId, threadId))
                _state.update { state ->
                    state.copy(thread = state.thread?.copy(activeTurnId = turnId))
                }
            }
        }
    }

    fun deleteSession(threadId: String) {
        val serviceId = _state.value.selectedServiceId ?: return
        launchOperation("正在删除会话") {
            newEmptyThreads.remove(threadKey(serviceId, threadId))
            api.deleteSession(serviceId, threadId)
            chatCache.delete(cacheScope, serviceId, threadId)
            if (_state.value.selectedThreadId == threadId) clearSelectedThread()
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

    fun browseHome() {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val knownHome = current.services.firstOrNull { it.id == serviceId }?.home
        _state.update { it.copy(remotePath = "", remoteFiles = emptyList()) }
        launchOperation("正在读取目录") {
            browseNow(serviceId, knownHome ?: api.home(serviceId))
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
        stopViewingThread(notifyGateway = false)
        events?.close(1000, "Android client closed")
        sessionSearchJob?.cancel()
        contextFileSearchJob?.cancel()
        chatCache.close()
    }

    private fun startViewingThread(serviceId: String, threadId: String): Boolean {
        if (
            !appForeground ||
            _state.value.section != MainSection.SESSIONS ||
            _state.value.showingArchivedSessions
        ) return false
        val current = viewedThread
        if (current?.serviceId == serviceId && current.threadId == threadId) return true
        stopViewingThread()
        val target = ViewedThread(serviceId, threadId, UUID.randomUUID().toString())
        viewedThread = target
        leaseHeartbeatJob = viewModelScope.launch {
            while (viewedThread == target) {
                runCatching {
                    api.acquireLease(target.serviceId, target.threadId, target.clientId)
                }.onSuccess {
                    if (viewedThread == target) {
                        _state.update { state ->
                            state.copy(
                                sessions = state.sessions.map { session ->
                                    if (session.id == target.threadId) {
                                        session.copy(locked = true)
                                    } else {
                                        session
                                    }
                                },
                            )
                        }
                    }
                }
                delay(30_000)
            }
        }
        return true
    }

    private fun stopViewingThread(notifyGateway: Boolean = true): Job? {
        val target = viewedThread ?: return null
        viewedThread = null
        val heartbeat = leaseHeartbeatJob
        heartbeat?.cancel()
        leaseHeartbeatJob = null
        if (notifyGateway) {
            viewModelScope.launch {
                heartbeat?.join()
                runCatching {
                    api.leaveLease(target.serviceId, target.threadId, target.clientId)
                }
            }
        }
        return heartbeat
    }

    private fun resumeViewingSelectedThread() {
        if (!appForeground) return
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return
        val threadId = current.thread?.id ?: return
        if (current.showingArchivedSessions) return
        val key = threadKey(serviceId, threadId)
        if (key in subscribedThreads) {
            startViewingThread(serviceId, threadId)
            return
        }
        if (!pendingThreadResumes.add(key)) return
        launchOperation("正在恢复会话") {
            try {
                loadThread(serviceId, threadId, resume = true, allowCache = true)
            } catch (error: GatewayException) {
                if (!showThreadTakeover(error, serviceId, threadId)) throw error
            } finally {
                pendingThreadResumes.remove(key)
            }
        }
    }

    private suspend fun deferThreadRelease(serviceId: String, threadId: String) {
        val clientId = UUID.randomUUID().toString()
        runCatching {
            api.acquireLease(serviceId, threadId, clientId)
            api.leaveLease(serviceId, threadId, clientId)
        }
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
        val collaborationModes = runCatching {
            parseCollaborationModes(api.collaborationModes(serviceId))
        }.getOrElse { emptyList() }
        val rateLimits = runCatching { parseRateLimits(api.rateLimits(serviceId)) }.getOrNull()
        _state.update {
            it.copy(
                models = models,
                collaborationModes = collaborationModes,
                rateLimits = rateLimits,
            )
        }
        val path = _state.value.services.firstOrNull { it.id == serviceId }?.home
            ?: api.home(serviceId)
        loadPermissionProfiles(serviceId, path)
        loadSessions(serviceId)
        loadPending(serviceId)
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

    private suspend fun loadPermissionProfiles(serviceId: String, cwd: String?) {
        val profiles = runCatching {
            parsePermissionProfiles(api.permissionProfiles(serviceId, cwd))
        }.getOrElse { emptyList() }
        if (_state.value.selectedServiceId == serviceId) {
            _state.update {
                it.copy(permissionProfiles = profiles.filter { profile -> profile.allowed })
            }
        }
    }

    private suspend fun loadThread(
        serviceId: String,
        threadId: String,
        resume: Boolean,
        allowCache: Boolean,
        takeover: Boolean = false,
    ) {
        val summary = _state.value.sessions.firstOrNull { it.id == threadId }
        val key = threadKey(serviceId, threadId)
        val alreadySubscribed = key in subscribedThreads && !takeover
        val cached = if (allowCache && alreadySubscribed && summary?.status != "active") {
            chatCache.get(cacheScope, serviceId, threadId, summary?.updatedAt ?: 0)
        } else null
        val root = cached ?: (when {
            takeover -> api.takeover(serviceId, threadId)
            resume && !alreadySubscribed -> api.resume(serviceId, threadId)
            else -> api.thread(serviceId, threadId)
        }).also {
            chatCache.put(cacheScope, serviceId, threadId, summary?.updatedAt ?: 0, it)
        }
        if (resume) subscribedThreads.add(key)
        val rawDetail = parseThread(root)
        val fallback = _state.value.thread
            ?.takeIf { it.id == threadId }
            ?.settings
            ?.toOptions()
            ?: (rawDetail.cwd ?: summary?.cwd)?.let { cwd ->
                sessionPreferences.load(cacheScope, serviceId, cwd)
            }
        val currentGoal = _state.value.thread?.takeIf { it.id == threadId }?.goal
        val goal = runCatching {
            parseThreadGoal(api.goal(serviceId, threadId).optJSONObject("goal"))
        }.getOrElse { currentGoal }
        val detail = rawDetail.copy(
            settings = resolveThreadSettings(root, rawDetail.settings, fallback),
            goal = goal,
        )
        if (detail.messages.any { it.role == MessageRole.USER } || detail.turns.isNotEmpty()) {
            newEmptyThreads.remove(threadKey(serviceId, threadId))
        }
        if (
            _state.value.selectedServiceId != serviceId ||
            _state.value.selectedThreadId != threadId
        ) {
            if (resume) deferThreadRelease(serviceId, threadId)
            return
        }
        _state.update { state ->
            state.copy(
                thread = detail.copy(
                    tokenUsage = state.thread?.takeIf { it.id == detail.id }?.tokenUsage,
                ),
            )
        }
        rememberThreadSettings(serviceId, detail.settings)
        if (resume && !startViewingThread(serviceId, threadId)) {
            deferThreadRelease(serviceId, threadId)
        }
        loadPermissionProfiles(serviceId, detail.cwd)
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
                        turnId = payload.optString("turnId"),
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
                        turnId = payload.optString("turnId"),
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
                        turnId = payload.optString("turnId"),
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
            "session.occupancy/changed" -> {
                val threadId = payload.optString("threadId")
                val serviceId = event.optString("serviceId")
                val locked = payload.optBoolean("locked")
                if (!locked && serviceId.isNotBlank()) {
                    subscribedThreads.remove(threadKey(serviceId, threadId))
                }
                _state.update { state ->
                    state.copy(
                        sessions = state.sessions.map { session ->
                            if (session.id == threadId) session.copy(locked = locked) else session
                        },
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
            "codex.thread/tokenUsage/updated" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                parseThreadTokenUsage(payload)?.let { tokenUsage ->
                    updateThread(threadId) { it.copy(tokenUsage = tokenUsage) }
                }
            }
            "codex.account/rateLimits/updated" -> {
                parseRateLimits(payload)?.let { limits ->
                    _state.update { it.copy(rateLimits = limits) }
                }
            }
            "codex.thread/settings/updated" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                val value = payload.optJSONObject("threadSettings") ?: return
                val settings = parseThreadSettings(value, _state.value.thread?.cwd)
                updateThread(threadId) { thread ->
                    thread.copy(cwd = settings.cwd ?: thread.cwd, settings = settings)
                }
                _state.value.selectedServiceId?.let { serviceId ->
                    rememberThreadSettings(serviceId, settings)
                    viewModelScope.launch {
                        runCatching { chatCache.delete(cacheScope, serviceId, threadId) }
                    }
                }
            }
            "codex.thread/goal/updated" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                parseThreadGoal(payload.optJSONObject("goal"))?.let { goal ->
                    updateThread(threadId) { it.copy(goal = goal) }
                }
            }
            "codex.thread/goal/cleared" -> {
                val threadId = payload.optString("threadId")
                if (threadId != selectedThread) return
                updateThread(threadId) { it.copy(goal = null) }
            }
            "codex.turn/started" -> {
                if (payload.optString("threadId") != selectedThread) return
                val turnJson = payload.optJSONObject("turn")
                val turnId = turnJson?.optString("id") ?: payload.optString("turnId")
                _state.update { state ->
                    state.copy(
                        thread = state.thread?.copy(
                            activeTurnId = turnId,
                            turns = turnJson?.let(::parseTurnSummary)?.let(state.thread.turns::upsert)
                                ?: state.thread.turns,
                        ),
                    )
                }
            }
            "codex.turn/completed", "codex.error" -> {
                val serviceId = _state.value.selectedServiceId ?: return
                val threadId = selectedThread
                if (threadId != null && payload.optString("threadId") == threadId) {
                    val completedTurn = payload.optJSONObject("turn")?.let(::parseTurnSummary)
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
                                turnId = payload.optString("turnId"),
                            ),
                            markActive = false,
                        )
                    }
                    updateThread(threadId) { thread ->
                        val turns = when {
                            completedTurn != null -> thread.turns.upsert(completedTurn)
                            type == "codex.error" -> thread.turns.map { turn ->
                                if (turn.id == payload.optString("turnId")) {
                                    turn.copy(status = "failed")
                                } else turn
                            }
                            else -> thread.turns
                        }
                        thread.copy(activeTurnId = null, turns = turns)
                    }
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
                if (type == "codex.thread/deleted") {
                    payload.optString("threadId").takeIf(String::isNotBlank)?.let { threadId ->
                        newEmptyThreads.remove(threadKey(serviceId, threadId))
                    }
                }
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
                turnId = payload.optString("turnId"),
            ),
        ) { message ->
            if (detail) {
                message.copy(detail = message.detail.orEmpty() + payload.optString("delta"))
            } else {
                val existing = message.text.takeUnless { it == "正在分析" }.orEmpty()
                message.copy(text = existing + payload.optString("delta"))
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
                thread.messages + transform(
                    fallback.copy(turnId = fallback.turnId ?: turnId.takeIf(String::isNotBlank)),
                )
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
        if (message.role == MessageRole.USER) {
            _state.value.selectedServiceId?.let { serviceId ->
                newEmptyThreads.remove(threadKey(serviceId, threadId))
            }
        }
        _state.update { state ->
            val thread = state.thread?.takeIf { it.id == threadId } ?: return@update state
            val attributedMessage = message.copy(
                turnId = message.turnId ?: turnId.takeIf(String::isNotBlank),
            )
            state.copy(
                thread = thread.copy(
                    messages = thread.messages.withRealtimeMessage(attributedMessage),
                    activeTurnId = if (markActive && turnId.isNotBlank()) turnId else thread.activeTurnId,
                ),
            )
        }
    }

    private fun initialSessionOptions(serviceId: String, cwd: String): NewSessionOptions {
        val selectedDirectory = cwd.trim()
        require(selectedDirectory.startsWith('/')) { "请选择有效的工作目录" }
        return sessionPreferences.load(cacheScope, serviceId, selectedDirectory)
            ?: NewSessionOptions(cwd = selectedDirectory, model = null, effort = null)
    }

    private fun clearSelectedThread() {
        stopViewingThread()
        _state.update {
            it.copy(
                selectedThreadId = null,
                thread = null,
                threadTakeoverPrompt = null,
            )
        }
    }

    private fun showThreadTakeover(
        error: GatewayException,
        serviceId: String,
        threadId: String,
    ): Boolean {
        if (error.code != "THREAD_IN_USE") return false
        val current = _state.value
        val summary = current.sessions.firstOrNull { it.id == threadId }
        val title = summary?.name?.takeIf(String::isNotBlank)
            ?: summary?.preview?.takeIf(String::isNotBlank)
            ?: "此会话"
        _state.update {
            it.copy(
                threadTakeoverPrompt = ThreadTakeoverPrompt(
                    serviceId = serviceId,
                    threadId = threadId,
                    title = title,
                    clearSelectionOnDismiss = current.thread?.id != threadId,
                ),
            )
        }
        return true
    }

    private fun discardSelectedEmptyThread(): Boolean {
        val current = _state.value
        val serviceId = current.selectedServiceId ?: return false
        val threadId = current.selectedThreadId ?: return false
        val key = threadKey(serviceId, threadId)
        if (key !in newEmptyThreads) return false

        if (viewedThread?.serviceId == serviceId && viewedThread?.threadId == threadId) {
            stopViewingThread()
        }

        val summary = current.sessions.firstOrNull { it.id == threadId }
        val hasConversation = current.thread?.let { thread ->
            thread.messages.any { it.role == MessageRole.USER } || thread.turns.isNotEmpty()
        } == true || !summary?.preview.isNullOrBlank()
        if (hasConversation) {
            newEmptyThreads.remove(key)
            return false
        }

        newEmptyThreads.remove(key)
        subscribedThreads.remove(key)
        val scope = cacheScope
        _state.update { state ->
            state.copy(
                sessions = state.sessions.filterNot { it.id == threadId },
                selectedThreadId = if (state.selectedThreadId == threadId) null else state.selectedThreadId,
                thread = state.thread?.takeUnless { it.id == threadId },
            )
        }
        viewModelScope.launch {
            runCatching {
                api.deleteSession(serviceId, threadId)
                chatCache.delete(scope, serviceId, threadId)
            }.onFailure { error ->
                _state.update {
                    it.copy(error = "清理空会话失败：${error.message ?: error}")
                }
            }
        }
        return true
    }

    private fun resolveThreadSettings(
        root: JSONObject,
        parsed: ThreadSettings,
        fallback: NewSessionOptions?,
    ): ThreadSettings {
        val hasRemoteSettings = root.has("model") ||
            root.has("reasoningEffort") ||
            root.has("approvalPolicy") ||
            root.has("sandbox") ||
            root.has("activePermissionProfile") ||
            root.has("collaborationMode")
        return if (hasRemoteSettings || fallback == null) {
            parsed
        } else {
            fallback.toThreadSettings()
        }
    }

    private fun rememberThreadSettings(serviceId: String, settings: ThreadSettings) {
        settings.toOptions()?.let { sessionPreferences.save(cacheScope, serviceId, it) }
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

private fun NewSessionOptions.toThreadSettings(): ThreadSettings = ThreadSettings(
    cwd = cwd,
    model = model,
    effort = effort,
    approvalPolicy = approvalPolicy,
    sandbox = sandbox,
    networkAccess = networkAccess,
    permissionProfile = permissionProfile,
)

private fun ThreadSettings.toOptions(): NewSessionOptions? {
    val directory = cwd?.takeIf { it.startsWith('/') } ?: return null
    return NewSessionOptions(
        cwd = directory,
        model = model,
        effort = effort,
        approvalPolicy = approvalPolicy,
        sandbox = sandbox,
        networkAccess = networkAccess,
        permissionProfile = permissionProfile,
    )
}

private fun ThreadSettings.apply(update: ThreadSettingsUpdate): ThreadSettings = copy(
    cwd = update.cwd ?: cwd,
    model = update.collaborationMode?.model ?: update.model ?: model,
    effort = update.collaborationMode?.effort ?: update.effort ?: effort,
    approvalPolicy = update.approvalPolicy ?: approvalPolicy,
    sandbox = update.sandbox ?: sandbox,
    networkAccess = update.networkAccess ?: networkAccess,
    permissionProfile = when {
        update.permissionProfile != null -> update.permissionProfile
        update.sandbox != null -> null
        else -> permissionProfile
    },
    collaborationMode = update.collaborationMode?.mode ?: collaborationMode,
)

internal fun List<ChatMessage>.withOptimisticUserMessage(message: ChatMessage): List<ChatMessage> {
    val alreadyReceived = any { current ->
        current.role == MessageRole.USER &&
            current.text == message.text &&
            (
                current.turnId == message.turnId && message.turnId?.isNotBlank() == true ||
                    message.turnId.isNullOrBlank() &&
                    current.status in setOf("inProgress", "running", "active")
            )
    }
    return if (alreadyReceived) this else this + message
}

internal fun List<ChatMessage>.withRealtimeMessage(message: ChatMessage): List<ChatMessage> {
    val messages = if (message.role == MessageRole.USER) {
        filterNot { current ->
            current.id.startsWith("local-") &&
                current.role == MessageRole.USER &&
                current.text == message.text &&
                (
                    current.turnId == message.turnId ||
                        current.turnId.isNullOrBlank() ||
                        message.turnId.isNullOrBlank()
                )
        }
    } else {
        this
    }
    val index = messages.indexOfLast { it.id == message.id }
    return if (index >= 0) {
        messages.toMutableList().also { it[index] = message }
    } else {
        messages + message
    }
}

private data class ViewedThread(
    val serviceId: String,
    val threadId: String,
    val clientId: String,
)

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

private fun List<TurnSummary>.upsert(turn: TurnSummary): List<TurnSummary> {
    val index = indexOfLast { it.id == turn.id }
    if (index < 0) return this + turn
    return toMutableList().also { it[index] = turn }
}

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
