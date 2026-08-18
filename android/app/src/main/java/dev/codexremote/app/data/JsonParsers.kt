package dev.codexremote.app.data

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.AccountRateLimits
import dev.codexremote.app.model.CollaborationModeOption
import dev.codexremote.app.model.CommandActionSummary
import dev.codexremote.app.model.FileChangeSummary
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.PendingOption
import dev.codexremote.app.model.PendingQuestion
import dev.codexremote.app.model.PendingRequest
import dev.codexremote.app.model.PermissionProfile
import dev.codexremote.app.model.PlanStep
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileMatch
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.RateLimitWindow
import dev.codexremote.app.model.SessionSummary
import dev.codexremote.app.model.SkillOption
import dev.codexremote.app.model.ThreadDetail
import dev.codexremote.app.model.ThreadGoal
import dev.codexremote.app.model.ThreadSettings
import dev.codexremote.app.model.ThreadTokenUsage
import dev.codexremote.app.model.TurnSummary
import org.json.JSONArray
import org.json.JSONObject

fun parseServices(array: JSONArray): List<RemoteService> = array.objects().map { value ->
    val runtime = value.optJSONObject("runtime") ?: JSONObject()
    RemoteService(
        id = value.getString("id"),
        name = value.getString("name"),
        hostname = value.nullableString("hostname"),
        platform = value.nullableString("platform"),
        arch = value.nullableString("arch"),
        agentVersion = value.nullableString("agentVersion"),
        home = value.nullableString("home"),
        runtimeState = if (runtime.optString("state") == "connected") {
            RuntimeState.CONNECTED
        } else RuntimeState.DISCONNECTED,
        runtimeMessage = runtime.nullableString("message"),
    )
}

fun parseModels(root: JSONObject): List<ModelOption> =
    root.optJSONArray("data")?.objects()?.map { value ->
        val efforts = value.optJSONArray("supportedReasoningEfforts")
            ?.objects()
            ?.mapNotNull { it.nullableString("reasoningEffort") }
            .orEmpty()
        ModelOption(
            id = value.optString("id", value.optString("model")),
            displayName = value.optString("displayName", value.optString("id")),
            efforts = efforts,
            defaultEffort = value.nullableString("defaultReasoningEffort"),
            isDefault = value.optBoolean("isDefault"),
        )
    }.orEmpty()

fun parseCollaborationModes(root: JSONObject): List<CollaborationModeOption> =
    root.optJSONArray("data")?.objects()?.mapNotNull { value ->
        val mode = value.nullableString("mode") ?: return@mapNotNull null
        CollaborationModeOption(
            name = value.optString("name", mode),
            mode = mode,
            model = value.nullableString("model"),
            effort = value.nullableString("reasoning_effort"),
        )
    }.orEmpty()

fun parseRateLimits(root: JSONObject): AccountRateLimits? {
    val limits = root.optJSONObject("rateLimits") ?: return null
    fun window(name: String): RateLimitWindow? = limits.optJSONObject(name)?.let { value ->
        RateLimitWindow(
            usedPercent = value.optDouble("usedPercent"),
            windowDurationMins = value.nullableLong("windowDurationMins"),
            resetsAt = value.nullableLong("resetsAt"),
        )
    }
    return AccountRateLimits(
        primary = window("primary"),
        secondary = window("secondary"),
        planType = limits.nullableString("planType"),
    )
}

fun parseFileSearchResults(root: JSONObject): List<RemoteFileMatch> =
    root.optJSONArray("files")?.objects()?.mapNotNull { value ->
        val resultPath = value.optString("path")
        val rootPath = value.optString("root").trimEnd('/')
        if (resultPath.isBlank()) return@mapNotNull null
        val absolutePath = if (resultPath.startsWith('/')) resultPath else "$rootPath/$resultPath"
        RemoteFileMatch(
            name = value.optString("file_name", resultPath.substringAfterLast('/')),
            path = absolutePath,
            type = if (value.optString("match_type") == "directory") {
                RemoteFileType.DIRECTORY
            } else {
                RemoteFileType.FILE
            },
        )
    }.orEmpty()

fun parsePermissionProfiles(root: JSONObject): List<PermissionProfile> =
    root.optJSONArray("data")?.objects()?.map { value ->
        PermissionProfile(
            id = value.optString("id"),
            description = value.nullableString("description"),
            allowed = value.optBoolean("allowed", true),
        )
    }.orEmpty()

fun parseSkills(root: JSONObject): List<SkillOption> =
    root.optJSONArray("data")?.objects()?.flatMap { entry ->
        entry.optJSONArray("skills")?.objects().orEmpty().map { value ->
            val interfaceInfo = value.optJSONObject("interface") ?: JSONObject()
            SkillOption(
                name = value.optString("name"),
                displayName = interfaceInfo.nullableString("displayName")
                    ?: value.optString("name"),
                description = interfaceInfo.nullableString("shortDescription")
                    ?: value.nullableString("shortDescription")
                    ?: value.optString("description"),
                path = value.optString("path"),
                enabled = value.optBoolean("enabled", true),
            )
        }
    }.orEmpty()

fun parseSessions(root: JSONObject): List<SessionSummary> =
    root.optJSONArray("data")?.objects()?.map(::parseSession).orEmpty()

fun parseSession(value: JSONObject): SessionSummary = SessionSummary(
    id = value.getString("id"),
    name = value.nullableString("name"),
    preview = value.optString("preview"),
    cwd = value.nullableString("cwd"),
    updatedAt = value.optLong("updatedAt", value.optLong("createdAt")),
    status = value.optJSONObject("status")?.optString("type", "notLoaded") ?: "notLoaded",
    isPinned = value.optBoolean("isPinned"),
    locked = value.optBoolean("locked"),
)

fun parseThread(root: JSONObject): ThreadDetail {
    val thread = root.getJSONObject("thread")
    val turns = thread.optJSONArray("turns")?.objects().orEmpty()
    val messages = buildList {
        turns.forEach { turn ->
            val turnId = turn.optString("id")
            val status = turn.optString("status")
            val items = turn.optJSONArray("items")?.objects().orEmpty()
            items.forEachIndexed { index, item ->
                val itemStatus = if (status == "inProgress" && index < items.lastIndex) {
                    "completed"
                } else {
                    status
                }
                parseChatItem(turnId, itemStatus, index, item)?.let(::add)
            }
            turn.optJSONObject("error")?.nullableString("message")?.let { error ->
                add(
                    ChatMessage(
                        "$turnId-error",
                        MessageRole.SYSTEM,
                        error,
                        "failed",
                        "error",
                        turnId = turnId,
                    ),
                )
            }
        }
    }
    val activeTurn = turns
        .lastOrNull { it.optString("status") == "inProgress" }
        ?.optString("id")
    val status = thread.optJSONObject("status") ?: JSONObject()
    return ThreadDetail(
        id = thread.getString("id"),
        name = thread.nullableString("name"),
        cwd = thread.nullableString("cwd"),
        status = status.optString("type", "notLoaded"),
        messages = messages,
        activeTurnId = activeTurn,
        activeFlags = status.optJSONArray("activeFlags")?.strings()?.toSet().orEmpty(),
        turns = turns.map(::parseTurnSummary),
        settings = parseThreadSettings(root, thread.nullableString("cwd")),
    )
}

fun parseThreadSettings(value: JSONObject, fallbackCwd: String? = null): ThreadSettings {
    val sandbox = value.optJSONObject("sandbox")
        ?: value.optJSONObject("sandboxPolicy")
        ?: JSONObject()
    val sandboxType = when (sandbox.optString("type")) {
        "dangerFullAccess" -> "danger-full-access"
        "readOnly" -> "read-only"
        "workspaceWrite" -> "workspace-write"
        "externalSandbox" -> "external-sandbox"
        else -> "workspace-write"
    }
    val permissionProfile = value.optJSONObject("activePermissionProfile")
        ?.nullableString("id")
    val collaboration = value.optJSONObject("collaborationMode")
    val collaborationMode = collaboration?.nullableString("mode")
        ?: "default"
    return ThreadSettings(
        cwd = value.nullableString("cwd") ?: fallbackCwd,
        model = value.nullableString("model"),
        effort = value.nullableString("reasoningEffort")
            ?: value.nullableString("effort")
            ?: collaboration?.optJSONObject("settings")?.nullableString("reasoning_effort"),
        approvalPolicy = value.optString("approvalPolicy", "on-request"),
        sandbox = sandboxType,
        networkAccess = sandbox.optBoolean("networkAccess", true),
        permissionProfile = permissionProfile,
        collaborationMode = collaborationMode,
    )
}

fun parseThreadGoal(value: JSONObject?): ThreadGoal? {
    if (value == null || value == JSONObject.NULL) return null
    val threadId = value.optString("threadId")
    val objective = value.optString("objective")
    if (threadId.isBlank() || objective.isBlank()) return null
    return ThreadGoal(
        threadId = threadId,
        objective = objective,
        status = value.optString("status", "active"),
        tokenBudget = value.nullableLong("tokenBudget"),
        tokensUsed = value.optLong("tokensUsed"),
        timeUsedSeconds = value.optLong("timeUsedSeconds"),
        createdAt = value.optLong("createdAt"),
        updatedAt = value.optLong("updatedAt"),
    )
}

fun parseTurnSummary(turn: JSONObject): TurnSummary = TurnSummary(
    id = turn.optString("id"),
    status = turn.optString("status"),
    startedAtMs = turn.nullableLong("startedAt")?.times(1_000),
    completedAtMs = turn.nullableLong("completedAt")?.times(1_000),
    durationMs = turn.nullableLong("durationMs"),
)

fun parseThreadTokenUsage(payload: JSONObject): ThreadTokenUsage? {
    val usage = payload.optJSONObject("tokenUsage") ?: return null
    val usedTokens = usage.optJSONObject("last")?.nullableLong("totalTokens") ?: return null
    val contextWindow = usage.nullableLong("modelContextWindow") ?: return null
    if (usedTokens < 0 || contextWindow <= 0) return null
    return ThreadTokenUsage(usedTokens = usedTokens, contextWindow = contextWindow)
}

fun parseChatItem(
    turnId: String,
    turnStatus: String,
    index: Int,
    item: JSONObject,
): ChatMessage? {
    val type = item.optString("type")
    val id = item.optString("id", "$turnId-$index")
    val message = when (type) {
        "userMessage" -> {
            val text = item.optJSONArray("content")?.objects()?.joinToString("\n") { content ->
                when (content.optString("type")) {
                    "text" -> content.optString("text")
                    "image", "audio" -> content.optString("url")
                    "localImage", "localAudio" -> content.optString("path")
                    "skill" -> "@" + content.optString("name")
                    "mention" -> content.optString("path", content.optString("name"))
                    else -> content.optString("path", content.optString("name"))
                }
            }.orEmpty()
            ChatMessage(id, MessageRole.USER, text, turnStatus, type)
        }
        "agentMessage" -> ChatMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            text = item.optString("text"),
            status = turnStatus,
            kind = type,
            phase = item.nullableString("phase"),
        )
        "plan" -> ChatMessage(
            id = id,
            role = MessageRole.SYSTEM,
            text = item.optString("text"),
            status = turnStatus,
            kind = type,
            title = "计划",
        )
        "reasoning" -> {
            val summary = item.optJSONArray("summary")?.strings()?.joinToString("\n").orEmpty()
            val content = item.optJSONArray("content")?.strings()?.joinToString("\n").orEmpty()
            ChatMessage(
                id = id,
                role = MessageRole.SYSTEM,
                text = summary.ifBlank { "正在分析" },
                status = turnStatus,
                kind = type,
                title = "分析",
                detail = content.takeIf(String::isNotBlank),
            )
        }
        "commandExecution" -> {
            val command = item.optString("command")
            val output = item.optString("aggregatedOutput")
            ChatMessage(
                id = id,
                role = MessageRole.TOOL,
                text = command,
                status = item.optString("status"),
                kind = type,
                title = "命令",
                detail = output.takeIf(String::isNotBlank),
                cwd = item.nullableString("cwd"),
                exitCode = item.nullableInt("exitCode"),
                durationMs = item.nullableLong("durationMs"),
                commandActions = item.optJSONArray("commandActions")?.objects()?.map { action ->
                    CommandActionSummary(
                        type = action.optString("type"),
                        path = action.nullableString("path"),
                        name = action.nullableString("name"),
                        query = action.nullableString("query"),
                    )
                }.orEmpty(),
            )
        }
        "fileChange" -> {
            val changes = item.optJSONArray("changes")?.objects()?.map { change ->
                FileChangeSummary(
                    path = change.optString("path"),
                    kind = change.optString("kind", change.optString("type")),
                    diff = change.optString("diff"),
                )
            }.orEmpty()
            ChatMessage(
                id = id,
                role = MessageRole.TOOL,
                text = if (changes.isEmpty()) "文件已变更" else "${changes.size} 个文件已变更",
                status = item.optString("status"),
                kind = type,
                title = "文件变更",
                changes = changes,
            )
        }
        "mcpToolCall", "dynamicToolCall", "collabAgentToolCall" -> {
            val tool = item.optString("tool", "协作任务")
            val namespace = item.optString("server", item.optString("namespace"))
            val arguments = item.opt("arguments")?.prettyJson().orEmpty()
            val result = sequenceOf("result", "contentItems", "error")
                .mapNotNull { key -> item.opt(key)?.prettyJson()?.takeIf(String::isNotBlank) }
                .firstOrNull()
            ChatMessage(
                id = id,
                role = MessageRole.TOOL,
                text = listOf(namespace, tool).filter(String::isNotBlank).joinToString(" / "),
                status = item.optString("status"),
                kind = type,
                title = if (type == "collabAgentToolCall") "协作任务" else "工具调用",
                detail = listOf(arguments, result.orEmpty()).filter(String::isNotBlank).joinToString("\n")
                    .takeIf(String::isNotBlank),
                durationMs = item.nullableLong("durationMs"),
            )
        }
        "webSearch" -> ChatMessage(
            id,
            MessageRole.TOOL,
            item.optString("query", item.optJSONObject("action")?.toString(2).orEmpty()),
            turnStatus,
            type,
            title = "网页搜索",
        )
        "subAgentActivity" -> ChatMessage(
            id = id,
            role = MessageRole.TOOL,
            text = item.optString("agentPath", item.optString("agentThreadId")),
            status = turnStatus,
            kind = type,
            title = "子任务",
            detail = item.opt("kind")?.prettyJson(),
        )
        "imageView" -> ChatMessage(
            id = id,
            role = MessageRole.TOOL,
            text = item.optString("path"),
            status = turnStatus,
            kind = type,
            title = "查看图片",
        )
        "imageGeneration" -> ChatMessage(
            id = id,
            role = MessageRole.TOOL,
            text = item.optString("revisedPrompt", item.optString("prompt", "生成图片")),
            status = item.optString("status", turnStatus),
            kind = type,
            title = "图片生成",
        )
        "sleep" -> ChatMessage(
            id = id,
            role = MessageRole.TOOL,
            text = "等待 Codex 继续执行",
            status = item.optString("status", turnStatus),
            kind = type,
            title = "等待",
        )
        "hookPrompt" -> ChatMessage(
            id = id,
            role = MessageRole.SYSTEM,
            text = "已应用 Hook 上下文",
            status = turnStatus,
            kind = type,
            title = "Hook",
        )
        "enteredReviewMode", "exitedReviewMode" -> ChatMessage(
            id,
            MessageRole.SYSTEM,
            item.optString("review").ifBlank {
                if (type == "enteredReviewMode") "开始代码审阅" else "代码审阅结束"
            },
            turnStatus,
            type,
            title = "代码审阅",
        )
        "contextCompaction" -> ChatMessage(
            id,
            MessageRole.SYSTEM,
            if (turnStatus == "inProgress") "正在压缩上下文" else "上下文已压缩",
            turnStatus,
            type,
            title = "上下文",
        )
        else -> null
    }
    return message?.copy(turnId = turnId)
}

fun parsePlanSteps(array: JSONArray?): List<PlanStep> =
    array?.objects()?.map { PlanStep(it.optString("step"), it.optString("status")) }.orEmpty()

fun parseFiles(array: JSONArray): List<RemoteFile> = array.objects().map { value ->
    RemoteFile(
        name = value.getString("name"),
        path = value.getString("path"),
        type = when (value.optString("type")) {
            "directory" -> RemoteFileType.DIRECTORY
            "file" -> RemoteFileType.FILE
            "symlink" -> RemoteFileType.SYMLINK
            else -> RemoteFileType.OTHER
        },
        size = value.optLong("size"),
        modifiedAt = value.optLong("modifiedAt"),
        permissions = value.optInt("permissions"),
    )
}

fun parsePending(array: JSONArray): List<PendingRequest> = array.objects().map { value ->
    val params = value.optJSONObject("params") ?: JSONObject()
    val method = value.optString("method")
    val command = params.optString("command")
    val reason = params.optString("reason")
    val questions = params.optJSONArray("questions")?.objects()?.map { question ->
        PendingQuestion(
            id = question.optString("id"),
            header = question.optString("header"),
            question = question.optString("question"),
            isOther = question.optBoolean("isOther"),
            isSecret = question.optBoolean("isSecret"),
            options = question.optJSONArray("options")?.objects()?.map { option ->
                PendingOption(
                    label = option.optString("label"),
                    description = option.optString("description"),
                )
            }.orEmpty(),
        )
    }.orEmpty()
    val title = when {
        method.contains("commandExecution") -> "命令审批"
        method.contains("fileChange") -> "文件变更审批"
        method.contains("permissions") -> "权限请求"
        method.contains("requestUserInput") -> "Codex 需要输入"
        method.contains("elicitation") -> "工具确认"
        else -> "Codex 请求"
    }
    PendingRequest(
        requestId = value.getString("requestId"),
        serviceId = value.getString("serviceId"),
        method = method,
        threadId = params.nullableString("threadId"),
        turnId = params.nullableString("turnId"),
        title = title,
        detail = listOf(
            reason,
            command,
            params.nullableString("cwd"),
            params.nullableString("grantRoot"),
        ).filterNotNull().filter(String::isNotBlank).joinToString("\n")
            .ifBlank { params.toString(2) },
        createdAt = value.optString("createdAt"),
        questions = questions,
        permissionsJson = params.optJSONObject("permissions")?.toString(),
        elicitationMode = params.nullableString("mode"),
        paramsJson = params.toString(),
    )
}

fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun Any.prettyJson(): String = when (this) {
    JSONObject.NULL -> ""
    is JSONObject -> toString(2)
    is JSONArray -> toString(2)
    else -> toString()
}
