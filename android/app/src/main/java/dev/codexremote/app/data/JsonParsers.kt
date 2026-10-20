package dev.codexremote.app.data

import dev.codexremote.app.model.RemoteFile
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
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
        capabilities = value.optJSONArray("capabilities")?.let { entries ->
            (0 until entries.length()).mapNotNull { entries.optString(it).takeIf(String::isNotBlank) }.toSet()
        },
    )
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

fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
