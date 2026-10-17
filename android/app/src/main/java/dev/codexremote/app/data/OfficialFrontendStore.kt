package dev.codexremote.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.GZIPInputStream

data class OfficialFrontend(val version: String, val directory: File, val slot: String)
data class OfficialVersion(val version: String, val downloadUrl: String, val preRelease: Boolean)

/** Two shared slots. Official resources stay outside the APK and are never edited. */
class OfficialFrontendStore(private val context: Context) {
    private val root = File(context.filesDir, "official-frontends")
    private val preferences = context.getSharedPreferences("official-frontends", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.MINUTES).build()

    fun mode(scope: String): String = preferences.getString("mode-$scope", "latest") ?: "latest"

    fun setMode(scope: String, mode: String) {
        require(mode == "latest" || mode == "specified")
        preferences.edit { putString("mode-$scope", mode) }
    }

    fun workspace(scope: String, home: String): String = preferences.getString("workspace-$scope", home) ?: home

    fun setWorkspace(scope: String, path: String) {
        require(path.startsWith('/')) { "工作目录必须为远端绝对路径" }
        preferences.edit { putString("workspace-$scope", path) }
    }

    fun clientId(scope: String): String = preferences.getString("client-$scope", null)
        ?: UUID.randomUUID().toString().also { id -> preferences.edit { putString("client-$scope", id) } }

    fun retain(frontend: OfficialFrontend) = synchronized(activeVersions) {
        activeVersions[frontend.version] = (activeVersions[frontend.version] ?: 0) + 1
    }

    fun release(frontend: OfficialFrontend) = synchronized(activeVersions) {
        val remaining = (activeVersions[frontend.version] ?: 1) - 1
        if (remaining <= 0) activeVersions.remove(frontend.version) else activeVersions[frontend.version] = remaining
    }

    fun cached(slot: String): OfficialFrontend? {
        requireSlot(slot)
        val version = preferences.getString("slot-$slot", null) ?: return null
        val directory = File(File(root, "versions"), version)
        val metadata = File(directory, "frontend.json")
        if (!metadata.isFile || !File(directory, "index.html").isFile) return null
        return runCatching {
            OfficialFrontend(JSONObject(metadata.readText()).getString("version"), directory, slot)
        }.getOrNull()
    }

    suspend fun versions(): List<OfficialVersion> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("filters", JSONArray().put(JSONObject()
            .put("criteria", JSONArray().put(JSONObject().put("filterType", 7).put("value", "OpenAI.chatgpt")))
            .put("pageNumber", 1).put("pageSize", 1))).put("flags", 147)
        val request = Request.Builder()
            .url("https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery")
            .header("Accept", "application/json;api-version=7.2-preview.1")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        withCall(request) { response ->
            check(response.isSuccessful) { "官方版本查询失败：HTTP ${response.code}" }
            val extension = JSONObject(response.body.string()).getJSONArray("results")
                .getJSONObject(0).getJSONArray("extensions").getJSONObject(0)
            check(extension.getJSONObject("publisher").getString("publisherName").equals("openai", true)
                && extension.getString("extensionName") == "chatgpt") { "官方插件身份不匹配" }
            extension.getJSONArray("versions").objects().filter {
                it.optString("targetPlatform") == "linux-x64"
            }.mapNotNull { version ->
                val download = version.optJSONArray("files")?.objects()?.firstOrNull {
                    it.optString("assetType") == "Microsoft.VisualStudio.Services.VSIXPackage"
                }?.optString("source") ?: return@mapNotNull null
                val preRelease = version.optJSONArray("properties")?.objects()?.any {
                    it.optString("key") == "Microsoft.VisualStudio.Code.PreRelease" && it.optString("value") == "true"
                } == true
                OfficialVersion(version.getString("version"), download, preRelease)
            }.distinctBy { it.version }
        }
    }

    suspend fun install(slot: String, version: OfficialVersion, progress: (String) -> Unit): OfficialFrontend =
        downloadLock.withLock {
            withContext(Dispatchers.IO) {
                requireSlot(slot)
                val binding = adapter(version.version)
                cached(slot)?.takeIf { it.version == version.version }?.let { return@withContext it }
                val versionsRoot = File(root, "versions").apply { mkdirs() }
                val existing = File(versionsRoot, version.version)
                if (File(existing, "frontend.json").isFile && File(existing, "index.html").isFile) {
                    preferences.edit(commit = true) { putString("slot-$slot", version.version) }
                    pruneVersions(versionsRoot)
                    return@withContext OfficialFrontend(version.version, existing, slot)
                }
                root.mkdirs()
                val staging = File(root, ".install-${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val url = java.net.URI(version.downloadUrl)
                    require(url.scheme == "https" && (url.host == "marketplace.visualstudio.com" ||
                        url.host.endsWith(".vsassets.io"))) { "非官方插件下载地址" }
                    progress("正在下载 ${version.version}")
                    val request = Request.Builder().url(version.downloadUrl).build()
                    withCall(request) { response ->
                        check(response.isSuccessful) { "插件下载失败：HTTP ${response.code}" }
                        val manifest = StringBuilder()
                        var extracted = 0L
                        val source = PushbackInputStream(response.body.byteStream().buffered(), 2)
                        val first = source.read()
                        val second = source.read()
                        if (second >= 0) source.unread(second)
                        if (first >= 0) source.unread(first)
                        val archive = if (first == 0x1f && second == 0x8b) GZIPInputStream(source) else source
                        ZipInputStream(archive).use { zip ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val entry = zip.nextEntry ?: break
                                if (entry.name == "extension/package.json") {
                                    val bytes = java.io.ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        check(bytes.size() + count <= 1024 * 1024) { "插件清单超过大小限制" }
                                        bytes.write(buffer, 0, count)
                                    }
                                    manifest.append(bytes.toString("UTF-8"))
                                } else if (entry.name.startsWith("extension/webview/")) {
                                    val relative = entry.name.removePrefix("extension/webview/")
                                    if (relative.isBlank()) { zip.closeEntry(); continue }
                                    val target = File(staging, relative).canonicalFile
                                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "插件资源路径无效" }
                                    if (entry.isDirectory) target.mkdirs() else {
                                        target.parentFile?.mkdirs()
                                        target.outputStream().buffered().use { output ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                val count = zip.read(buffer)
                                                if (count < 0) break
                                                extracted += count
                                                check(extracted <= 1024L * 1024 * 1024) { "插件资源超过 1 GiB" }
                                                output.write(buffer, 0, count)
                                            }
                                        }
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                        val metadata = JSONObject(manifest.toString())
                        check(metadata.getString("publisher").equals("openai", true) && metadata.getString("name") == "chatgpt"
                            && metadata.getString("version") == version.version) { "插件包版本或发布者不匹配" }
                    }
                    progress("正在校验官方前端")
                    currentCoroutineContext().ensureActive()
                    check(File(staging, "index.html").isFile) { "插件缺少前端入口" }
                    val bundle = File(staging, binding.getString("bundle"))
                    check(bundle.isFile && digest(bundle) == binding.getString("sha256")) { "前端适配文件校验失败" }
                    File(staging, "frontend.json").writeText(JSONObject().put("version", version.version).toString())
                    currentCoroutineContext().ensureActive()
                    val target = File(versionsRoot, version.version)
                    check(staging.renameTo(target)) { "无法保存官方前端" }
                    preferences.edit(commit = true) { putString("slot-$slot", version.version) }
                    pruneVersions(versionsRoot)
                    OfficialFrontend(version.version, target, slot)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    fun adapter(version: String): JSONObject {
        val catalog = context.assets.open("adapters.json").bufferedReader().use { JSONObject(it.readText()) }
        return catalog.getJSONObject("versions").optJSONObject(version)
            ?: error("官方前端 $version 尚未适配；请选择已支持的指定版本。")
    }

    fun supportedVersions(): Set<String> = context.assets.open("adapters.json").bufferedReader().use {
        JSONObject(it.readText()).getJSONObject("versions").keys().asSequence().toSet()
    }

    private fun requireSlot(slot: String) = require(slot == "latest" || slot == "specified")

    private fun pruneVersions(directory: File) {
        val referenced = listOfNotNull(preferences.getString("slot-latest", null), preferences.getString("slot-specified", null)).toSet()
        synchronized(activeVersions) {
            directory.listFiles()?.filter { it.name !in referenced && it.name !in activeVersions }?.forEach { it.deleteRecursively() }
        }
    }

    private suspend fun <T> withCall(request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { call.execute().use { block(it) } } finally { cancellation.cancel() }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val downloadLock = Mutex()
        val activeVersions = mutableMapOf<String, Int>()
    }
}

fun frontendScope(config: dev.codexremote.app.model.GatewayConfig, serviceId: String): String =
    MessageDigest.getInstance("SHA-256").digest("${config.baseUrl}\u0000${config.token}\u0000$serviceId".toByteArray())
        .joinToString("") { "%02x".format(it) }
