package dev.codexremote.app

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import dev.codexremote.app.data.normalizeGatewayUrl
import kotlinx.coroutines.delay
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexremote.app.data.documentMetadata
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.OfficialFrontendStore
import dev.codexremote.app.data.SecretStore
import dev.codexremote.app.data.TerminalSession
import dev.codexremote.app.data.TerminalSessionManager
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.data.parseFiles
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.data.joinRemotePath
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.AppTheme
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException

/** Native shell state only. Conversations and approvals belong to the official frontend. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val secretStore = SecretStore(application)
    private val uiPreferences = UiPreferences(application)
    internal val gatewayApi = GatewayApi()
    private val api get() = gatewayApi
    internal val officialFrontends = OfficialFrontendStore(application)
    private val terminalSessions = TerminalSessionManager(api)
    private val _state = MutableStateFlow(AppState(fontScale = uiPreferences.fontScale, maxChatPages = uiPreferences.maxChatPages, theme = uiPreferences.theme, allowHttp = uiPreferences.allowHttp, multipleEndpoints = uiPreferences.multipleEndpoints))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var activeOperations = 0
    private var configurationGeneration = 0
    private var connectionJob: Job? = null
    private var operationScope = newOperationScope()

    private var monitoringJob: Job? = null
    private var networkJob: Job? = null
    private var foreground = false
    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    @Volatile private var lastNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val changed = lastNetwork != null && lastNetwork != network
            lastNetwork = network
            networkChanged(changed)
        }
        override fun onLost(network: Network) { networkChanged(forceReconnect = true) }
    }

    private fun networkChanged(forceReconnect: Boolean = false) {
        viewModelScope.launch {
            if (!foreground) return@launch
            networkJob?.cancel()
            networkJob = viewModelScope.launch {
                delay(700) // Coalesce the old-network loss and new-network availability callbacks.
                try { api.checkConnection(preferPrimary = true, reconnect = forceReconnect) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The link indicator shows failure; existing sockets retry. */ }
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        monitoringJob?.cancel()
        if (!value) {
            networkJob?.cancel()
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            return
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        monitoringJob = viewModelScope.launch {
            while (true) {
                try { api.checkConnection() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                delay(15_000)
            }
        }
    }

    fun reconnectGateway() {
        if (networkJob?.isActive == true) return
        networkJob = viewModelScope.launch {
            try { api.checkConnection(preferPrimary = true, reconnect = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(error = error.message) } }
        }
    }

    fun setExperiments(http: Boolean, multiple: Boolean) {
        uiPreferences.setExperiments(http, multiple)
        val config = _state.value.gatewayConfig?.copy(allowHttp = http, multipleEndpoints = multiple)
        config?.let { secretStore.save(it); api.updatePolicy(it) }
        _state.update { it.copy(allowHttp = http, multipleEndpoints = multiple, gatewayConfig = config) }
        networkChanged()
    }

    init {
        secretStore.load()?.let { saved ->
            val config = saved.copy(allowHttp = uiPreferences.allowHttp, multipleEndpoints = uiPreferences.multipleEndpoints)
            api.updatePolicy(config)
            _state.update { it.copy(configured = true, gatewayConfig = config) }
            launchOperation("正在连接 Gateway") {
                api.checkConnection(preferPrimary = true)
                applyMeta(api.meta())
                loadServicesAndSelect()
            }
        }
    }

    fun configureGateway(address: String, token: String, endpoints: String = "") {
        if (_state.value.connecting) return
        val generation = configurationGeneration
        _state.update { it.copy(connecting = true, error = null) }
        connectionJob = viewModelScope.launch {
            try {
                val config = GatewayConfig(normalizeGatewayUrl(address), token.trim(),
                    allowHttp = _state.value.allowHttp, multipleEndpoints = _state.value.multipleEndpoints,
                    endpoints = endpoints.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::normalizeGatewayUrl).distinct().toList())
                require(config.token.isNotBlank()) { "访问令牌不能为空" }
                val selection = api.selectConfiguration(config)
                if (generation != configurationGeneration) return@launch
                secretStore.save(config)
                val previous = _state.value.gatewayConfig
                if (previous?.baseUrl == config.baseUrl && previous.token == config.token) {
                    api.updatePolicy(config)
                    _state.update { it.copy(gatewayConfig = config) }
                    api.checkConnection(preferPrimary = true)
                    applyMeta(selection.metadata)
                    return@launch
                }
                configurationGeneration += 1
                resetOperations()
                terminalSessions.closeAll()
                api.configure(config, selection.endpoint, selection.latencyMs)
                _state.update { AppState(configured = true, gatewayConfig = config, fontScale = it.fontScale, maxChatPages = it.maxChatPages, theme = it.theme, allowHttp = it.allowHttp, multipleEndpoints = it.multipleEndpoints) }
                applyMeta(selection.metadata)
                launchOperation("正在刷新服务") { loadServicesAndSelect() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == configurationGeneration) _state.update { it.copy(error = error.message ?: error.toString()) }
            } finally {
                if (generation == configurationGeneration) _state.update { it.copy(connecting = false) }
            }
        }
    }

    fun clearGateway() {
        configurationGeneration += 1
        connectionJob?.cancel()
        resetOperations()
        terminalSessions.closeAll()
        api.clear()
        secretStore.clear()
        _state.value = AppState(fontScale = uiPreferences.fontScale, maxChatPages = uiPreferences.maxChatPages, theme = uiPreferences.theme, allowHttp = uiPreferences.allowHttp, multipleEndpoints = uiPreferences.multipleEndpoints)
    }

    fun setSection(section: MainSection) {
        _state.update { current ->
            if (section == MainSection.TERMINAL && current.services.firstOrNull { it.id == current.selectedServiceId }?.supportsTerminal == false) current
            else current.copy(section = section)
        }
    }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun updateFontScale(value: Float) { _state.update { it.copy(fontScale = uiPreferences.setFontScale(value)) } }
    fun setMaxChatPages(value: Int) { _state.update { it.copy(maxChatPages = uiPreferences.setMaxChatPages(value)) } }
    fun setTheme(value: AppTheme) {
        uiPreferences.setTheme(value)
        _state.update { it.copy(theme = value) }
    }

    fun refreshServices() { launchOperation("正在刷新服务") { loadServicesAndSelect() } }

    fun refreshGatewayMetadata() { launchOperation("正在刷新网关参数") { applyMeta(api.meta()) } }

    fun deleteService(serviceId: String) {
        launchOperation("正在删除服务") {
            terminalSessions.close(serviceId)
            api.deleteService(serviceId)
            if (_state.value.selectedServiceId == serviceId) {
                _state.update { it.copy(selectedServiceId = null, remotePath = "", remoteFiles = emptyList(), filePreview = null) }
            }
            loadServicesAndSelect()
        }
    }

    fun testService(serviceId: String) {
        launchOperation("正在测试 Agent 与 Codex") { api.codexRuntime(serviceId); loadServicesAndSelect() }
    }

    fun selectService(serviceId: String) {
        if (_state.value.selectedServiceId == serviceId) return
        _state.update { current ->
            val unsupportedTerminal = current.services.firstOrNull { it.id == serviceId }?.supportsTerminal == false
            current.copy(selectedServiceId = serviceId, remotePath = "", remoteFiles = emptyList(), filePreview = null,
                section = if (current.section == MainSection.TERMINAL && unsupportedTerminal) MainSection.SESSIONS else current.section)
        }
        launchOperation("正在读取目录") { loadServiceWorkspace(serviceId) }
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
            api.mkdir(serviceId, joinRemotePath(currentPath, name))
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
        val metadata = resolver.documentMetadata(uri)
        val name = remoteName?.takeIf(String::isNotBlank) ?: metadata.first
        val directory = _state.value.remotePath
        launchOperation("正在上传 " + name) {
            val remotePath = joinRemotePath(directory, name)
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
                        content = payload,
                    ),
                )
            }
        }
    }

    fun closePreview() {
        _state.update { it.copy(filePreview = null) }
    }

    internal fun terminalSession(serviceId: String, cwd: String?): TerminalSession {
        check(_state.value.services.firstOrNull { it.id == serviceId }?.supportsTerminal != false) { "该服务器不支持远程终端" }
        return terminalSessions.session(serviceId, cwd)
    }

    override fun onCleared() { setForeground(false); terminalSessions.closeAll(); api.clear(); operationScope.coroutineContext[Job]?.cancel() }

    private fun applyMeta(meta: org.json.JSONObject) {
        val limits = meta.optJSONObject("limits")
        val auth = meta.optJSONObject("auth")
        val capabilities = meta.optJSONArray("capabilities")
        _state.update { it.copy(
            maxDownloadBytes = limits?.optLong("downloadBytes", 10L * 1024 * 1024) ?: 10L * 1024 * 1024,
            maxUploadBytes = limits?.optLong("uploadBytes", 10L * 1024 * 1024) ?: 10L * 1024 * 1024,
            gatewayRole = auth?.optString("role"),
            gatewayTokenId = auth?.optString("tokenId"),
            gatewayAdminSupported = capabilities != null && (0 until capabilities.length()).any { index ->
                capabilities.optString(index) == "gateway-admin-v1"
            },
        ) }
    }

    private suspend fun loadServicesAndSelect() {
        val services = parseServices(api.services())
        val selected = _state.value.selectedServiceId?.takeIf { id -> services.any { it.id == id } }
            ?: services.firstOrNull { it.runtimeState == RuntimeState.CONNECTED }?.id
            ?: services.firstOrNull()?.id
        services.filterNot { it.supportsTerminal }.forEach { terminalSessions.close(it.id) }
        _state.update { current ->
            current.copy(services = services, selectedServiceId = selected,
                section = if (current.section == MainSection.TERMINAL && services.firstOrNull { it.id == selected }?.supportsTerminal == false) MainSection.SESSIONS else current.section)
        }
        if (selected != null && services.any { it.id == selected && it.runtimeState == RuntimeState.CONNECTED }) {
            if (_state.value.remotePath.isBlank()) loadServiceWorkspace(selected)
        } else _state.update { it.copy(section = MainSection.SERVICES) }
    }

    private suspend fun loadServiceWorkspace(serviceId: String) {
        val path = _state.value.services.firstOrNull { it.id == serviceId }?.home ?: api.home(serviceId)
        browseNow(serviceId, path)
    }

    private suspend fun browseNow(serviceId: String, path: String) {
        val files = parseFiles(api.files(serviceId, path))
        if (_state.value.selectedServiceId == serviceId) {
            _state.update { it.copy(remotePath = path, remoteFiles = files, filePreview = null) }
        }
    }

    private fun launchOperation(label: String, block: suspend () -> Unit) {
        val generation = configurationGeneration
        operationScope.launch {
            activeOperations += 1
            _state.update { it.copy(loading = true, operation = label, error = null) }
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == configurationGeneration) _state.update { it.copy(error = error.message ?: error.toString()) }
            } finally {
                if (generation == configurationGeneration) {
                    activeOperations = (activeOperations - 1).coerceAtLeast(0)
                    _state.update { it.copy(loading = activeOperations > 0, operation = if (activeOperations > 0) it.operation else null) }
                }
            }
        }
    }

    private fun resetOperations() {
        operationScope.coroutineContext[Job]?.cancel()
        operationScope = newOperationScope()
        activeOperations = 0
    }

    private fun newOperationScope() = CoroutineScope(
        viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]),
    )
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
