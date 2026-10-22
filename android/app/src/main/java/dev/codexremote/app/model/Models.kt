package dev.codexremote.app.model

import dev.codexremote.app.data.PreviewContent

enum class MainSection { SERVICES, SESSIONS, TERMINAL, FILES, SETTINGS }

enum class AppTheme(val id: String) { DARK("dark"), LIGHT("light"), ANTHROPIC("anthropic") }

data class GatewayConfig(
    val baseUrl: String,
    val token: String,
    val allowHttp: Boolean = false,
    val multipleEndpoints: Boolean = false,
    val endpoints: List<String> = emptyList(),
)

enum class RuntimeState { CONNECTED, DISCONNECTED }

/** Tool selection belongs to a window; official business messages remain opaque. */
enum class CodingTool(val id: String, val title: String) {
    CODEX("codex", "Codex"), CLAUDE("claude", "Claude Code");

    fun supports(service: RemoteService): Boolean = this == CODEX || service.platform == "linux" ||
        (service.platform == "darwin" && service.capabilities?.contains("claude-extension-host-v1") == true)

    companion object {
        fun fromId(id: String): CodingTool? = entries.firstOrNull { it.id == id }
        fun title(id: String): String = fromId(id)?.title ?: id
    }
}

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
    val capabilities: Set<String>? = null,
) {
    val supportsTerminal: Boolean get() = platform != "win32" && (capabilities == null || "terminal" in capabilities)
}

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
    val content: PreviewContent,
)

/** Host window identity; conversations remain opaque state owned by the official frontend. */
data class WorkspaceWindow(
    val id: String,
    val serviceId: String,
    val workspace: String,
    val title: String,
    val toolId: String = "codex",
)

data class AppState(
    val allowHttp: Boolean = false,
    val multipleEndpoints: Boolean = false,
    val configured: Boolean = false,
    val gatewayConfig: GatewayConfig? = null,
    val fontScale: Float = 1f,
    val maxChatPages: Int = 5,
    val windowMode: Boolean = true,
    val customWindowLimit: Boolean = false,
    val customCacheLimit: Boolean = false,
    val resourceCacheBytes: Long = 1024L * 1024 * 1024,
    val theme: AppTheme = AppTheme.DARK,
    val section: MainSection = MainSection.SESSIONS,
    val connecting: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val services: List<RemoteService> = emptyList(),
    val selectedServiceId: String? = null,
    val windows: List<WorkspaceWindow> = emptyList(),
    val selectedWindowId: String? = null,
    val recentWindowIds: List<String> = emptyList(),
    val busyWindowIds: Set<String> = emptySet(),
    val windowRevisions: Map<String, Int> = emptyMap(),
    val remotePath: String = "",
    val remoteFiles: List<RemoteFile> = emptyList(),
    val filePreview: FilePreview? = null,
    val maxDownloadBytes: Long = 10L * 1024 * 1024,
    val maxUploadBytes: Long = 10L * 1024 * 1024,
    val gatewayRole: String? = null,
    val gatewayTokenId: String? = null,
    val gatewayAdminSupported: Boolean = false,
    val operation: String? = null,
)
