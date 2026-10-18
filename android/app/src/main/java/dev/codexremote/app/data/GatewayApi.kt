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
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
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

    suspend fun adminTokens(): JSONArray = data(request("GET", "api/v1/admin/tokens")) as JSONArray

    suspend fun createAdminToken(label: String, role: String): JSONObject = data(request("POST", "api/v1/admin/tokens",
        JSONObject().put("label", label).put("role", role))) as JSONObject

    suspend fun revokeAdminToken(id: String) { request("DELETE", "api/v1/admin/tokens/$id") }

    suspend fun adminSettings(): JSONObject = data(request("GET", "api/v1/admin/settings")) as JSONObject

    suspend fun saveAdminSettings(uploadBytes: Long, downloadBytes: Long): JSONObject =
        data(request("PUT", "api/v1/admin/settings", JSONObject()
            .put("maxUploadBytes", uploadBytes).put("maxDownloadBytes", downloadBytes))) as JSONObject

    suspend fun adminOverview(): JSONObject = data(request("GET", "api/v1/admin/overview")) as JSONObject

    suspend fun adminServices(): JSONArray = data(request("GET", "api/v1/admin/services")) as JSONArray

    suspend fun deleteAdminService(ownerId: String, serviceId: String) {
        request("DELETE", "api/v1/admin/services/$ownerId/$serviceId")
    }

    suspend fun gatewayRuntime(checkUpdates: Boolean = false): JSONObject = data(request("GET", "api/v1/admin/runtime",
        query = if (checkUpdates) mapOf("checkUpdates" to "true") else emptyMap())) as JSONObject

    suspend fun gatewayOperation(update: Boolean): JSONObject = data(request("POST",
        "api/v1/admin/${if (update) "update" else "restart"}", JSONObject().put("confirmed", true))) as JSONObject

    suspend fun codexRuntime(serviceId: String): JSONObject =
        data(request("GET", "api/v2/services/$serviceId/codex/runtime")) as JSONObject

    suspend fun updateCodex(serviceId: String): JSONObject =
        data(request("POST", "api/v2/services/$serviceId/codex/update", JSONObject())) as JSONObject

    suspend fun restartCodex(serviceId: String, confirmed: Boolean): JSONObject =
        data(request("POST", "api/v2/services/$serviceId/codex/restart", JSONObject().put("confirmed", confirmed))) as JSONObject

    suspend fun extensionStatus(serviceId: String): JSONObject =
        data(request("GET", "api/v3/services/$serviceId/extension/status")) as JSONObject

    suspend fun updateExtension(serviceId: String): JSONObject =
        data(request("POST", "api/v3/services/$serviceId/extension/update", JSONObject())) as JSONObject

    suspend fun applyExtension(serviceId: String, confirmed: Boolean): JSONObject =
        data(request("POST", "api/v3/services/$serviceId/extension/apply", JSONObject().put("confirmed", confirmed))) as JSONObject

    fun extensionChannel(
        serviceId: String, clientId: String, rendererId: String, workspace: String,
        after: Long, epoch: String?, listener: WebSocketListener,
    ): WebSocket = client.newWebSocket(
        Request.Builder().url(url("api/v3/services/$serviceId/extension", buildMap {
            put("clientId", clientId)
            put("rendererId", rendererId)
            put("workspace", workspace)
            put("after", after.toString())
            epoch?.let { put("epoch", it) }
        })).authorized().build(), listener,
    )

    /** Runs on WebView's resource thread. Never exposes the Gateway token to page JavaScript. */
    internal fun extensionResource(serviceId: String, version: String, path: String, packageSha256: String? = null): ByteArray {
        val current = requireNotNull(config) { "Gateway is not configured" }
        val target = current.baseUrl.ensureTrailingSlash().toHttpUrl().newBuilder()
            .addPathSegments("api/v3/services/$serviceId/extension/resource")
            .addQueryParameter("version", version).addQueryParameter("path", path)
        packageSha256?.let { target.addQueryParameter("packageSha256", it) }
        return execute(Request.Builder().url(target.build())
            .header("Authorization", "Bearer " + current.token).get().build()).use { response ->
            ensureSuccess(response)
            val bytes = response.body.bytes()
            val expected = response.header("x-resource-sha256")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(expected != null && actual.equals(expected, ignoreCase = true)) { "官方前端资源校验失败" }
            if (packageSha256 != null) require(packageSha256.equals(response.header("x-extension-package-sha256"), ignoreCase = true)) {
                "官方前端包已更换，请重新连接"
            }
            bytes
        }
    }

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

    suspend fun preview(serviceId: String, path: String): PreviewContent =
        withContext(Dispatchers.IO) {
            execute(
                Request.Builder()
                    .url(url("api/v1/services/$serviceId/fs/download", mapOf("path" to path)))
                    .authorized().get().build(),
            ).use { response ->
                ensureSuccess(response)
                response.body.byteStream().use(::readPreview)
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
        executeCancellable(builder.build()).use { response ->
            ensureSuccess(response)
            if (response.code == 204) JSONObject() else parseObject(response)
        }
    }

    private fun execute(request: Request): Response = client.newCall(request).execute()

    private suspend fun executeCancellable(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
            }
        })
    }

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
    }
}

class GatewayException(
    val statusCode: Int,
    val code: String,
    override val message: String,
    val details: JSONObject? = null,
) : Exception(message)

private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
