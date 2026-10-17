package dev.codexremote.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun ContentResolver.documentMetadata(uri: Uri): Pair<String, Long> {
    var name = "upload.bin"
    var size = -1L
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    // Document providers control display names. Keep them within the selected directory.
    name = name.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' }
    if (name.isBlank() || name == "." || name == "..") name = "upload.bin"
    return name to size
}

internal suspend fun uploadAttachments(
    api: GatewayApi, serviceId: String, home: String, resolver: ContentResolver,
    uris: List<Uri>, params: JSONObject, onStatus: (String?) -> Unit,
): JSONArray {
    val files = JSONArray()
    if (uris.isEmpty()) return files
    val extensions = params.optJSONArray("acceptedFileExtensions")?.let { values ->
        (0 until values.length()).map { values.getString(it).trimStart('.').lowercase() }
    }.orEmpty()
    val metadata = withContext(Dispatchers.IO) { uris.map { resolver.documentMetadata(it) } }
    metadata.forEachIndexed { index, (name, _) ->
        require(extensions.isEmpty() || extensions.any { name.endsWith(".$it", ignoreCase = true) }) { "不支持的文件类型：$name" }
        if (params.optBoolean("imagesOnly")) require(resolver.getType(uris[index])?.startsWith("image/") == true) { "请选择图片：$name" }
    }
    try {
        val root = "${home.trimEnd('/')}/.codex-remote-uploads"
        if (api.files(serviceId, home).objects().none { it.optString("name") == ".codex-remote-uploads" }) {
            try { api.mkdir(serviceId, root) }
            catch (failure: GatewayException) {
                if (api.files(serviceId, home).objects().none { it.optString("name") == ".codex-remote-uploads" && it.optString("type") == "directory" }) throw failure
            }
        }
        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (name, size) = metadata[index]
            onStatus("正在上传 ${index + 1}/${uris.size}：$name")
            val directory = "$root/${UUID.randomUUID()}"
            api.mkdir(serviceId, directory)
            val path = "$directory/$name"
            api.upload(serviceId, path, name, resolver.getType(uri), size) {
                resolver.openInputStream(uri) ?: error("无法读取 $name")
            }
            currentCoroutineContext().ensureActive()
            files.put(JSONObject().put("label", name).put("path", path).put("fsPath", path))
        }
    } finally { onStatus(null) }
    return files
}
