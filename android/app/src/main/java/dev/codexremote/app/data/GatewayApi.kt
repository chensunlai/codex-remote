package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.NewSessionOptions
import dev.codexremote.app.model.PromptContext
import dev.codexremote.app.model.PromptContextType
import dev.codexremote.app.model.ThreadSettingsUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class GatewayApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var config: GatewayConfig? = null

    fun configure(value: GatewayConfig) {
        config = value.copy(baseUrl = value.baseUrl.trimEnd('/'))
    }

    fun clear() {
        config = null
        client.dispatcher.cancelAll()
    }

    suspend fun meta(): JSONObject = data(request("GET", "api/v1/meta")) as JSONObject

    suspend fun services(): JSONArray = data(request("GET", "api/v1/services")) as JSONArray

    suspend fun deleteService(serviceId: String) {
        request("DELETE", "api/v1/services/$serviceId")
    }

    suspend fun testService(serviceId: String): JSONObject =
        data(request("POST", "api/v1/services/$serviceId/test", JSONObject())) as JSONObject

    suspend fun models(serviceId: String): JSONObject =
        data(request("GET", "api/v1/services/$serviceId/models")) as JSONObject

    suspend fun sessions(
        serviceId: String,
        archived: Boolean = false,
        searchTerm: String? = null,
    ): JSONObject =
        data(
            request(
                "GET",
                "api/v1/services/$serviceId/sessions",
                query = buildMap {
                    put("limit", "100")
                    put("archived", archived.toString())
                    searchTerm?.trim()?.takeIf(String::isNotBlank)?.let { put("searchTerm", it) }
                },
            ),
        ) as JSONObject

    suspend fun skills(serviceId: String, cwd: String): JSONObject =
        data(
            request(
                "GET",
                "api/v1/services/$serviceId/skills",
                query = mapOf("cwd" to cwd),
            ),
        ) as JSONObject

    suspend fun createSession(serviceId: String, options: NewSessionOptions): JSONObject {
        val body = JSONObject()
            .put("cwd", options.cwd)
            .put("approvalPolicy", options.approvalPolicy)
            .putIfNotBlank("model", options.model)
            .putIfNotBlank("effort", options.effort)
        if (options.permissionProfile.isNullOrBlank()) {
            body.put("sandbox", options.sandbox)
                .put("networkAccess", options.networkAccess)
        } else {
            body.put("permissions", options.permissionProfile)
        }
        return data(request("POST", "api/v1/services/$serviceId/sessions", body)) as JSONObject
    }

    suspend fun thread(serviceId: String, threadId: String): JSONObject =
        data(request("GET", "api/v1/services/$serviceId/sessions/$threadId")) as JSONObject

    suspend fun resume(serviceId: String, threadId: String): JSONObject =
        data(
            request(
                "POST",
                "api/v1/services/$serviceId/sessions/$threadId/resume",
                JSONObject(),
            ),
        ) as JSONObject

    suspend fun permissionProfiles(serviceId: String, cwd: String?): JSONObject =
        data(
            request(
                "GET",
                "api/v1/services/$serviceId/permission-profiles",
                query = cwd?.takeIf(String::isNotBlank)?.let { mapOf("cwd" to it) }.orEmpty(),
            ),
        ) as JSONObject

    suspend fun updateThreadSettings(
        serviceId: String,
        threadId: String,
        update: ThreadSettingsUpdate,
    ) {
        val body = JSONObject()
            .putIfNotBlank("cwd", update.cwd)
            .putIfNotBlank("model", update.model)
            .putIfNotBlank("effort", update.effort)
            .putIfNotBlank("approvalPolicy", update.approvalPolicy)
            .putIfNotBlank("permissions", update.permissionProfile)
            .putIfNotBlank("sandbox", update.sandbox)
        update.networkAccess?.let { body.put("networkAccess", it) }
        request("PUT", "api/v1/services/$serviceId/sessions/$threadId/settings", body)
    }

    suspend fun goal(serviceId: String, threadId: String): JSONObject =
        data(request("GET", "api/v1/services/$serviceId/sessions/$threadId/goal")) as JSONObject

    suspend fun setGoal(
        serviceId: String,
        threadId: String,
        objective: String? = null,
        status: String? = null,
    ): JSONObject {
        val body = JSONObject()
            .putIfNotBlank("objective", objective)
            .putIfNotBlank("status", status)
        return data(
            request("PUT", "api/v1/services/$serviceId/sessions/$threadId/goal", body),
        ) as JSONObject
    }

    suspend fun clearGoal(serviceId: String, threadId: String) {
        request("DELETE", "api/v1/services/$serviceId/sessions/$threadId/goal")
    }

    suspend fun renameSession(serviceId: String, threadId: String, name: String) {
        request(
            "PUT",
            "api/v1/services/$serviceId/sessions/$threadId/name",
            JSONObject().put("name", name),
        )
    }

    suspend fun archiveSession(serviceId: String, threadId: String) {
        request("POST", "api/v1/services/$serviceId/sessions/$threadId/archive", JSONObject())
    }

    suspend fun unarchiveSession(serviceId: String, threadId: String) {
        request("POST", "api/v1/services/$serviceId/sessions/$threadId/unarchive", JSONObject())
    }

    suspend fun compactSession(serviceId: String, threadId: String) {
        request("POST", "api/v1/services/$serviceId/sessions/$threadId/compact", JSONObject())
    }

    suspend fun reviewUncommitted(serviceId: String, threadId: String): JSONObject =
        data(
            request(
                "POST",
                "api/v1/services/$serviceId/sessions/$threadId/review",
                JSONObject().put(
                    "target",
                    JSONObject().put("type", "uncommittedChanges"),
                ),
            ),
        ) as JSONObject

    suspend fun deleteSession(serviceId: String, threadId: String) {
        request("DELETE", "api/v1/services/$serviceId/sessions/$threadId")
    }

    suspend fun sendTurn(
        serviceId: String,
        threadId: String,
        text: String,
        model: String?,
        effort: String?,
        context: List<PromptContext> = emptyList(),
    ): JSONObject {
        val body = JSONObject()
            .put("text", text)
            .put("context", context.toJson())
            .putIfNotBlank("model", model)
            .putIfNotBlank("effort", effort)
        return data(
            request("POST", "api/v1/services/$serviceId/sessions/$threadId/turns", body),
        ) as JSONObject
    }

    suspend fun steer(
        serviceId: String,
        threadId: String,
        turnId: String,
        text: String,
        context: List<PromptContext> = emptyList(),
    ) {
        request(
            "POST",
            "api/v1/services/$serviceId/sessions/$threadId/steer",
            JSONObject()
                .put("turnId", turnId)
                .put("text", text)
                .put("context", context.toJson()),
        )
    }

    suspend fun interrupt(serviceId: String, threadId: String, turnId: String) {
        request(
            "POST",
            "api/v1/services/$serviceId/sessions/$threadId/interrupt",
            JSONObject().put("turnId", turnId),
        )
    }

    suspend fun pendingRequests(serviceId: String?): JSONArray {
        val query = serviceId?.let { mapOf("serviceId" to it) } ?: emptyMap()
        return data(request("GET", "api/v1/requests", query = query)) as JSONArray
    }

    suspend fun respond(requestId: String, result: JSONObject) {
        request(
            "POST",
            "api/v1/requests/$requestId/respond",
            JSONObject().put("result", result),
        )
    }

    suspend fun home(serviceId: String): String =
        (data(request("GET", "api/v1/services/$serviceId/fs/home")) as JSONObject)
            .getString("path")

    suspend fun files(serviceId: String, path: String): JSONArray =
        data(
            request("GET", "api/v1/services/$serviceId/fs/list", query = mapOf("path" to path)),
        ) as JSONArray

    suspend fun mkdir(serviceId: String, path: String) {
        request(
            "POST",
            "api/v1/services/$serviceId/fs/directory",
            JSONObject().put("path", path),
        )
    }

    suspend fun deleteFile(serviceId: String, path: String, directory: Boolean) {
        request(
            "DELETE",
            "api/v1/services/$serviceId/fs",
            query = mapOf("path" to path, "directory" to directory.toString()),
        )
    }

    suspend fun download(serviceId: String, path: String, output: OutputStream) =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(url("api/v1/services/$serviceId/fs/download", mapOf("path" to path)))
                    .authorized()
                    .get()
                    .build(),
            ).use { response ->
                ensureSuccess(response)
                response.body.byteStream().use { input -> input.copyTo(output) }
            }
        }

    suspend fun preview(serviceId: String, path: String): PreviewPayload =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(url("api/v1/services/$serviceId/fs/preview", mapOf("path" to path)))
                    .authorized()
                    .get()
                    .build(),
            ).use { response ->
                ensureSuccess(response)
                val body = response.body
                val declaredLength = body.contentLength()
                val output = ByteArrayOutputStream(minOf(PREVIEW_RENDER_BYTES, 32 * 1024))
                var remaining = PREVIEW_RENDER_BYTES
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    val truncated = declaredLength > PREVIEW_RENDER_BYTES ||
                        (declaredLength < 0 && remaining == 0 && input.read() >= 0)
                    PreviewPayload(
                        output.toString(StandardCharsets.UTF_8.name()),
                        truncated,
                    )
                }
            }
        }

    suspend fun upload(
        serviceId: String,
        path: String,
        fileName: String,
        contentType: String?,
        contentLength: Long,
        open: () -> InputStream,
    ): JSONObject = withContext(Dispatchers.IO) {
        val fileBody = StreamingRequestBody(contentType, contentLength, open)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBody)
            .build()
        execute(
            Request.Builder()
                .url(url("api/v1/services/$serviceId/fs/upload", mapOf("path" to path)))
                .authorized()
                .post(multipart)
                .build(),
        ).use { response ->
            ensureSuccess(response)
            data(parseObject(response)) as JSONObject
        }
    }

    fun events(
        after: Long,
        serviceId: String?,
        listener: WebSocketListener,
    ): WebSocket {
        val query = buildMap {
            put("after", after.toString())
            serviceId?.let { put("serviceId", it) }
        }
        return client.newWebSocket(
            Request.Builder()
                .url(url("api/v1/events/stream", query))
                .authorized()
                .build(),
            listener,
        )
    }

    fun terminal(
        serviceId: String,
        cols: Int,
        rows: Int,
        cwd: String?,
        listener: WebSocketListener,
    ): WebSocket {
        val query = buildMap {
            put("cols", cols.toString())
            put("rows", rows.toString())
            cwd?.takeIf(String::isNotBlank)?.let { put("cwd", it) }
        }
        return client.newWebSocket(
            Request.Builder()
                .url(url("api/v1/services/$serviceId/terminal", query))
                .authorized()
                .build(),
            listener,
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder().url(url(path, query)).authorized()
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (requestBody == null) builder.delete() else builder.delete(requestBody)
            "POST" -> builder.post(requestBody ?: EMPTY_BODY)
            "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
            else -> error("Unsupported method: $method")
        }
        execute(builder.build()).use { response ->
            ensureSuccess(response)
            if (response.code == 204) JSONObject() else parseObject(response)
        }
    }

    private fun execute(request: Request): Response = client.newCall(request).execute()

    private fun parseObject(response: Response): JSONObject {
        val text = response.body.string()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun ensureSuccess(response: Response) {
        if (response.isSuccessful) return
        val text = response.body.string()
        val message = runCatching {
            JSONObject(text).getJSONObject("error").getString("message")
        }.getOrElse { text.ifBlank { "HTTP " + response.code } }
        throw GatewayException(response.code, message)
    }

    private fun data(root: JSONObject): Any = root.opt("data") ?: JSONObject.NULL

    private fun url(path: String, query: Map<String, String>): HttpUrl {
        val current = requireNotNull(config) { "Gateway is not configured" }
        val base = current.baseUrl.ensureTrailingSlash().toHttpUrl()
        val builder = base.newBuilder()
        path.trim('/').split('/').filter(String::isNotBlank).forEach(builder::addPathSegment)
        query.forEach(builder::addQueryParameter)
        return builder.build()
    }

    private fun Request.Builder.authorized(): Request.Builder {
        val current = requireNotNull(config) { "Gateway is not configured" }
        return header("Authorization", "Bearer " + current.token)
            .header("Accept", "application/json")
    }

    private class StreamingRequestBody(
        contentType: String?,
        private val length: Long,
        private val open: () -> InputStream,
    ) : RequestBody() {
        private val mediaType = contentType?.toMediaType()
        override fun contentType() = mediaType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            open().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = "{}".toRequestBody(JSON_MEDIA)
        const val PREVIEW_RENDER_BYTES = 256 * 1024
    }
}

data class PreviewPayload(val content: String, val truncated: Boolean)

class GatewayException(val statusCode: Int, override val message: String) : Exception(message)

private fun JSONObject.putIfNotBlank(key: String, value: String?): JSONObject {
    if (!value.isNullOrBlank()) put(key, value)
    return this
}

private fun List<PromptContext>.toJson(): JSONArray = JSONArray().also { array ->
    forEach { context ->
        array.put(
            JSONObject()
                .put(
                    "type",
                    when (context.type) {
                        PromptContextType.FILE -> "mention"
                        PromptContextType.IMAGE -> "localImage"
                        PromptContextType.SKILL -> "skill"
                    },
                )
                .put("path", context.path)
                .apply {
                    if (context.type != PromptContextType.IMAGE) put("name", context.name)
                },
        )
    }
}

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
