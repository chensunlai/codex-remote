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

data class ModelOption(
    val id: String,
    val displayName: String,
    val efforts: List<String>,
    val defaultEffort: String?,
    val isDefault: Boolean,
)

data class CollaborationModeOption(
    val name: String,
    val mode: String,
    val model: String?,
    val effort: String?,
)

data class CollaborationModeSelection(
    val mode: String,
    val model: String,
    val effort: String?,
)

data class SessionSummary(
    val id: String,
    val name: String?,
    val preview: String,
    val cwd: String?,
    val updatedAt: Long,
    val status: String,
    val isPinned: Boolean,
)

data class NewSessionOptions(
    val cwd: String,
    val model: String?,
    val effort: String?,
    val approvalPolicy: String = "on-request",
    val sandbox: String = "workspace-write",
    val networkAccess: Boolean = true,
    val permissionProfile: String? = null,
)

data class ThreadSettings(
    val cwd: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val approvalPolicy: String = "on-request",
    val sandbox: String = "workspace-write",
    val networkAccess: Boolean = true,
    val permissionProfile: String? = null,
    val collaborationMode: String = "default",
)

data class ThreadSettingsUpdate(
    val cwd: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val approvalPolicy: String? = null,
    val sandbox: String? = null,
    val networkAccess: Boolean? = null,
    val permissionProfile: String? = null,
    val collaborationMode: CollaborationModeSelection? = null,
)

data class PermissionProfile(
    val id: String,
    val description: String?,
    val allowed: Boolean,
)

data class ThreadGoal(
    val threadId: String,
    val objective: String,
    val status: String,
    val tokenBudget: Long?,
    val tokensUsed: Long,
    val timeUsedSeconds: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class PromptContextType { FILE, IMAGE, SKILL }

data class PromptContext(
    val type: PromptContextType,
    val name: String,
    val path: String,
)

data class SkillOption(
    val name: String,
    val displayName: String,
    val description: String,
    val path: String,
    val enabled: Boolean,
)

enum class MessageRole { USER, ASSISTANT, SYSTEM, TOOL }

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val status: String? = null,
    val kind: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val cwd: String? = null,
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    val changes: List<FileChangeSummary> = emptyList(),
    val phase: String? = null,
    val turnId: String? = null,
    val commandActions: List<CommandActionSummary> = emptyList(),
)

data class CommandActionSummary(
    val type: String,
    val path: String? = null,
    val name: String? = null,
    val query: String? = null,
)

data class FileChangeSummary(
    val path: String,
    val kind: String,
    val diff: String,
)

data class PlanStep(
    val step: String,
    val status: String,
)

data class TurnSummary(
    val id: String,
    val status: String,
    val startedAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val durationMs: Long? = null,
)

data class ThreadTokenUsage(
    val usedTokens: Long,
    val contextWindow: Long,
)

data class RateLimitWindow(
    val usedPercent: Double,
    val windowDurationMins: Long?,
    val resetsAt: Long?,
)

data class AccountRateLimits(
    val primary: RateLimitWindow?,
    val secondary: RateLimitWindow?,
    val planType: String?,
)

data class ThreadDetail(
    val id: String,
    val name: String?,
    val cwd: String?,
    val status: String,
    val messages: List<ChatMessage>,
    val activeTurnId: String?,
    val activeFlags: Set<String> = emptySet(),
    val latestDiff: String = "",
    val planExplanation: String? = null,
    val plan: List<PlanStep> = emptyList(),
    val turns: List<TurnSummary> = emptyList(),
    val tokenUsage: ThreadTokenUsage? = null,
    val settings: ThreadSettings = ThreadSettings(),
    val goal: ThreadGoal? = null,
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

data class RemoteFileMatch(
    val name: String,
    val path: String,
    val type: RemoteFileType,
)

data class FilePreview(
    val name: String,
    val path: String,
    val content: String,
    val truncated: Boolean,
)

data class PendingRequest(
    val requestId: String,
    val serviceId: String,
    val method: String,
    val threadId: String?,
    val turnId: String?,
    val title: String,
    val detail: String,
    val createdAt: String,
    val questions: List<PendingQuestion> = emptyList(),
    val permissionsJson: String? = null,
    val elicitationMode: String? = null,
    val paramsJson: String = "{}",
)

data class PendingQuestion(
    val id: String,
    val header: String,
    val question: String,
    val isOther: Boolean,
    val isSecret: Boolean,
    val options: List<PendingOption>,
)

data class PendingOption(
    val label: String,
    val description: String,
)

data class AppState(
    val configured: Boolean = false,
    val gatewayConfig: GatewayConfig? = null,
    val section: MainSection = MainSection.SESSIONS,
    val loading: Boolean = false,
    val error: String? = null,
    val services: List<RemoteService> = emptyList(),
    val selectedServiceId: String? = null,
    val models: List<ModelOption> = emptyList(),
    val collaborationModes: List<CollaborationModeOption> = emptyList(),
    val permissionProfiles: List<PermissionProfile> = emptyList(),
    val skills: List<SkillOption> = emptyList(),
    val sessions: List<SessionSummary> = emptyList(),
    val showingArchivedSessions: Boolean = false,
    val sessionSearch: String = "",
    val selectedThreadId: String? = null,
    val thread: ThreadDetail? = null,
    val remotePath: String = "",
    val remoteFiles: List<RemoteFile> = emptyList(),
    val contextFileSearchQuery: String = "",
    val contextFileSearchResults: List<RemoteFileMatch> = emptyList(),
    val filePreview: FilePreview? = null,
    val pendingRequests: List<PendingRequest> = emptyList(),
    val eventConnected: Boolean = false,
    val maxDownloadBytes: Long = 10L * 1024 * 1024,
    val rateLimits: AccountRateLimits? = null,
    val operation: String? = null,
)
