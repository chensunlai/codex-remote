package dev.codexremote.app.data

import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.PendingRequest
import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.SessionSummary
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

fun parseSessions(root: JSONObject): List<SessionSummary> =
    root.optJSONArray("data")?.objects()?.map { value ->
        SessionSummary(
            id = value.getString("id"),
            name = value.nullableString("name"),
            preview = value.optString("preview"),
            cwd = value.nullableString("cwd"),
            updatedAt = value.optLong("updatedAt", value.optLong("createdAt")),
            status = value.optJSONObject("status")?.optString("type", "notLoaded") ?: "notLoaded",
            isPinned = value.optBoolean("isPinned"),
        )
    }.orEmpty()

fun parseThread(root: JSONObject): ThreadDetail {
    val thread = root.getJSONObject("thread")
    val messages = buildList {
        thread.optJSONArray("turns")?.objects()?.forEach { turn ->
            val turnId = turn.optString("id")
            val status = turn.optString("status")
            turn.optJSONArray("items")?.objects()?.forEachIndexed { index, item ->
                parseItem(turnId, status, index, item)?.let(::add)
            }
            turn.optJSONObject("error")?.nullableString("message")?.let { error ->
                add(ChatMessage("$turnId-error", MessageRole.SYSTEM, error, "failed", "error"))
            }
        }
    }
    val activeTurn = thread.optJSONArray("turns")
        ?.objects()
        ?.lastOrNull { it.optString("status") == "inProgress" }
        ?.optString("id")
    return ThreadDetail(
        id = thread.getString("id"),
        name = thread.nullableString("name"),
        cwd = thread.nullableString("cwd"),
        status = thread.optJSONObject("status")?.optString("type", "notLoaded") ?: "notLoaded",
        messages = messages,
        activeTurnId = activeTurn,
    )
}

private fun parseItem(
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
                    else -> content.optString("path", content.optString("name"))
                }
            }.orEmpty()
            ChatMessage(id, MessageRole.USER, text, turnStatus, type)
        }
        "agentMessage" -> ChatMessage(id, MessageRole.ASSISTANT, item.optString("text"), turnStatus, type)
        "plan" -> ChatMessage(id, MessageRole.SYSTEM, item.optString("text"), turnStatus, type)
        "commandExecution" -> {
            val command = item.optString("command")
            val output = item.optString("aggregatedOutput")
            ChatMessage(
                id,
                MessageRole.TOOL,
                listOf(command, output).filter(String::isNotBlank).joinToString("\n"),
                item.optString("status"),
                type,
            )
        }
        "fileChange" -> {
            val changes = item.optJSONArray("changes")?.objects()?.joinToString("\n") { change ->
                val path = change.optString("path")
                val kind = change.optString("kind", change.optString("type"))
                "$kind  $path".trim()
            }.orEmpty()
            ChatMessage(id, MessageRole.TOOL, changes.ifBlank { "文件已变更" }, item.optString("status"), type)
        }
        "mcpToolCall", "dynamicToolCall", "collabAgentToolCall" -> {
            val tool = item.optString("tool")
            val server = item.optString("server")
            ChatMessage(
                id,
                MessageRole.TOOL,
                listOf(server, tool).filter(String::isNotBlank).joinToString(" / "),
                item.optString("status"),
                type,
            )
        }
        "webSearch" -> ChatMessage(
            id,
            MessageRole.TOOL,
            item.optString("query", item.optJSONObject("action")?.toString(2).orEmpty()),
            turnStatus,
            type,
        )
        "enteredReviewMode", "exitedReviewMode" -> ChatMessage(
            id,
            MessageRole.SYSTEM,
            item.optString("review"),
            turnStatus,
            type,
        )
        "contextCompaction" -> ChatMessage(id, MessageRole.SYSTEM, "上下文已压缩", turnStatus, type)
        else -> null
    }
}

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
        detail = listOf(reason, command).filter(String::isNotBlank).joinToString("\n")
            .ifBlank { params.toString(2) },
        createdAt = value.optString("createdAt"),
    )
}

fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
