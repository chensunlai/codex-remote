package dev.codexremote.app

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.OfficialFrontendStore
import dev.codexremote.app.data.SecretStore
import dev.codexremote.app.data.TerminalSession
import dev.codexremote.app.data.TerminalSessionManager
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.data.parseFiles
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.FilePreview
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.net.URI

/** Native shell state only. Conversations and approvals belong to the official frontend. */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val secretStore = SecretStore(application)
    private val uiPreferences = UiPreferences(application)
    internal val gatewayApi = GatewayApi()
    private val api get() = gatewayApi
    internal val officialFrontends = OfficialFrontendStore(application)
    private val terminalSessions = TerminalSessionManager(api)
    private val _state = MutableStateFlow(AppState(fontScale = uiPreferences.fontScale))
    val state: StateFlow<AppState> = _state.asStateFlow()
    private var activeOperations = 0
    private var configurationGeneration = 0

    init {
        secretStore.load()?.let { config ->
            api.configure(config)
            _state.update { it.copy(configured = true, gatewayConfig = config) }
            launchOperation("正在连接 Gateway") {
                applyMeta(api.meta())
                loadServicesAndSelect()
            }
        }
    }

    fun configureGateway(address: String, token: String) {
        configurationGeneration += 1
        val generation = configurationGeneration
        launchOperation("正在连接 Gateway") {
            val config = GatewayConfig(normalizeGatewayUrl(address), token.trim())
            require(config.token.isNotBlank()) { "访问令牌不能为空" }
            val meta = api.testConfiguration(config)
            if (generation != configurationGeneration) return@launchOperation
            terminalSessions.closeAll()
            api.configure(config)
            secretStore.save(config)
            _state.update { AppState(configured = true, gatewayConfig = config, fontScale = it.fontScale) }
            applyMeta(meta)
            loadServicesAndSelect()
        }
    }

    fun clearGateway() {
        configurationGeneration += 1
        terminalSessions.closeAll()
        api.clear()
        secretStore.clear()
        _state.value = AppState(fontScale = uiPreferences.fontScale)
    }

    fun setSection(section: MainSection) { _state.update { it.copy(section = section) } }
    fun clearError() { _state.update { it.copy(error = null) } }
    fun updateFontScale(value: Float) { _state.update { it.copy(fontScale = uiPreferences.setFontScale(value)) } }

    fun refreshServices() { launchOperation("正在刷新服务") { loadServicesAndSelect() } }

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
        _state.update { it.copy(selectedServiceId = serviceId, remotePath = "", remoteFiles = emptyList(), filePreview = null) }
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

    internal fun terminalSession(serviceId: String, cwd: String?): TerminalSession =
        terminalSessions.session(serviceId, cwd)

    override fun onCleared() { terminalSessions.closeAll() }

    private fun applyMeta(meta: org.json.JSONObject) {
        _state.update { it.copy(maxDownloadBytes = meta.optJSONObject("limits")?.optLong("maxDownloadBytes", 10L * 1024 * 1024) ?: 10L * 1024 * 1024) }
    }

    private suspend fun loadServicesAndSelect() {
        val services = parseServices(api.services())
        val selected = _state.value.selectedServiceId?.takeIf { id -> services.any { it.id == id } }
            ?: services.firstOrNull { it.runtimeState == RuntimeState.CONNECTED }?.id
            ?: services.firstOrNull()?.id
        _state.update { it.copy(services = services, selectedServiceId = selected) }
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
        viewModelScope.launch {
            activeOperations += 1
            _state.update { it.copy(loading = true, operation = label, error = null) }
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == configurationGeneration) _state.update { it.copy(error = error.message ?: error.toString()) }
            } finally {
                activeOperations = (activeOperations - 1).coerceAtLeast(0)
                _state.update { it.copy(loading = activeOperations > 0, operation = if (activeOperations > 0) it.operation else null) }
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
