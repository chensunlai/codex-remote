package dev.codexremote.app.data

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.FileChangeSummary
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.PendingOption
import dev.codexremote.app.model.PendingQuestion
import dev.codexremote.app.model.PendingRequest
import dev.codexremote.app.model.PlanStep
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.SessionSummary
import dev.codexremote.app.model.SkillOption
import dev.codexremote.app.model.ThreadDetail
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
)

fun parseThread(root: JSONObject): ThreadDetail {
    val thread = root.getJSONObject("thread")
    val turns = thread.optJSONArray("turns")?.objects().orEmpty()
    val messages = buildList {
        turns.forEach { turn ->
            val turnId = turn.optString("id")
            val status = turn.optString("status")
            turn.optJSONArray("items")?.objects()?.forEachIndexed { index, item ->
                parseChatItem(turnId, status, index, item)?.let(::add)
            }
            turn.optJSONObject("error")?.nullableString("message")?.let { error ->
                add(ChatMessage("$turnId-error", MessageRole.SYSTEM, error, "failed", "error"))
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
    )
}

fun parseChatItem(
    turnId: String,
    turnStatus: String,
    index: Int,
    item: JSONObject,
): ChatMessage? {
    val type = item.optString("type")
    val id = item.optString("id", "$turnId-$index")
    return when (type) {
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
            "上下文已压缩",
            turnStatus,
            type,
            title = "上下文",
        )
        else -> null
    }
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
