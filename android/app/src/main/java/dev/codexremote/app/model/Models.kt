package dev.codexremote.app.model

enum class MainSection { SERVICES, SESSIONS, TERMINAL, FILES, SETTINGS }

data class GatewayConfig(
    val baseUrl: String,
    val token: String,
)

enum class RuntimeState { CONNECTED, DISCONNECTED }

data class RemoteService(
    val id: String,
    val name: String,
    val hostname: String?,
    val platform: String?,
    val arch: String?,
    val agentVersion: String?,
    val home: String?,
    val runtimeState: RuntimeState,
    val runtimeMessage: String?,
)

enum class RemoteFileType { DIRECTORY, FILE, SYMLINK, OTHER }

data class RemoteFile(
    val name: String,
    val path: String,
    val type: RemoteFileType,
    val size: Long,
    val modifiedAt: Long,
    val permissions: Int,
)

data class FilePreview(
    val name: String,
    val path: String,
    val content: String,
    val truncated: Boolean,
)

data class AppState(
    val configured: Boolean = false,
    val gatewayConfig: GatewayConfig? = null,
    val fontScale: Float = 1f,
    val section: MainSection = MainSection.SESSIONS,
    val loading: Boolean = false,
    val error: String? = null,
    val services: List<RemoteService> = emptyList(),
    val selectedServiceId: String? = null,
    val remotePath: String = "",
    val remoteFiles: List<RemoteFile> = emptyList(),
    val filePreview: FilePreview? = null,
    val maxDownloadBytes: Long = 10L * 1024 * 1024,
    val operation: String? = null,
)
