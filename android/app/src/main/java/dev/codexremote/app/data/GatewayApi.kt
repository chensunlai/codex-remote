package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
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
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GatewayApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val configurationClient = client.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var config: GatewayConfig? = null

    fun configure(value: GatewayConfig) {
        client.dispatcher.cancelAll()
        config = value.copy(baseUrl = value.baseUrl.trimEnd('/'))
    }

    fun clear() {
        config = null
        client.dispatcher.cancelAll()
    }

    suspend fun meta(): JSONObject = data(request("GET", "api/v1/meta")) as JSONObject

    suspend fun testConfiguration(candidate: GatewayConfig): JSONObject = suspendCancellableCoroutine { continuation ->
        val target = candidate.baseUrl.ensureTrailingSlash().toHttpUrl().newBuilder()
            .addPathSegments("api/v1/meta").build()
        val request = Request.Builder().url(target)
            .header("Authorization", "Bearer " + candidate.token)
            .header("Accept", "application/json").build()
        val call = configurationClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        ensureSuccess(it)
                        data(parseObject(it)) as JSONObject
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    suspend fun services(): JSONArray = data(request("GET", "api/v1/services")) as JSONArray

    suspend fun codexRuntime(serviceId: String): JSONObject =
        data(request("GET", "api/v2/services/$serviceId/codex/runtime")) as JSONObject

    suspend fun updateCodex(serviceId: String): JSONObject =
        data(request("POST", "api/v2/services/$serviceId/codex/update", JSONObject())) as JSONObject

    suspend fun restartCodex(serviceId: String, confirmed: Boolean): JSONObject =
        data(request("POST", "api/v2/services/$serviceId/codex/restart", JSONObject().put("confirmed", confirmed))) as JSONObject

    fun codexChannel(
        serviceId: String,
        clientId: String,
        sessionId: String,
        sequence: Long,
        epoch: String?,
        listener: WebSocketListener,
    ): WebSocket {
        val query = buildMap {
            put("clientId", clientId)
            put("sessionId", sessionId)
            put("after", sequence.toString())
            if (epoch != null) put("epoch", epoch)
        }
        return client.newWebSocket(
            Request.Builder().url(url("api/v2/services/$serviceId/codex", query)).authorized().build(),
            listener,
        )
    }

    suspend fun deleteService(serviceId: String) {
        request("DELETE", "api/v1/services/$serviceId")
    }

    suspend fun testService(serviceId: String): JSONObject =
        data(request("POST", "api/v1/services/$serviceId/test", JSONObject())) as JSONObject

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
        val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        val code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "HTTP_ERROR"
        val message = error?.optString("message")?.takeIf(String::isNotBlank)
            ?: text.ifBlank { "HTTP " + response.code }
        throw GatewayException(response.code, code, message, error?.optJSONObject("details"))
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

class GatewayException(
    val statusCode: Int,
    val code: String,
    override val message: String,
    val details: JSONObject? = null,
) : Exception(message)

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
