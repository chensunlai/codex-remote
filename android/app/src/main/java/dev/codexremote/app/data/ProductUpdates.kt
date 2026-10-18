package dev.codexremote.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class ProductRelease(val version: String, val pageUrl: String, val apkUrl: String?)

/** Independent public client: Gateway credentials are never used for release checks. */
internal class ProductUpdates {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun latest(): ProductRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/chensunlai/codex-remote/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "codex-remote-android").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { if (response.code == 403 || response.code == 429) "GitHub 请求过于频繁，请稍后重试" else "检查更新失败（HTTP ${response.code}）" }
            parseProductRelease(JSONObject(response.body.string()))
        }
    }

    fun close() { client.dispatcher.cancelAll() }
}

internal fun parseProductRelease(value: JSONObject): ProductRelease {
    require(!value.optBoolean("prerelease") && !value.optBoolean("draft")) { "更新来源不是正式发布版本" }
    val version = value.getString("tag_name").removePrefix("v")
    require(stableVersion(version) != null) { "更新版本号无效" }
    val tag = "v$version"
    val page = value.getString("html_url")
    require(officialReleaseUrl(page, "/releases/tag/$tag")) { "更新链接无效" }
    val assets = value.optJSONArray("assets")
    val apk = if (assets == null) null else (0 until assets.length()).asSequence()
        .map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == "codex-remote-android.apk" }
        ?.optString("browser_download_url")?.takeIf {
            officialReleaseUrl(it, "/releases/download/$tag/codex-remote-android.apk")
        }
    return ProductRelease(version, page, apk)
}

internal fun officialReleaseUrl(value: String, suffix: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
        uri.query == null && uri.fragment == null && uri.rawPath == "/chensunlai/codex-remote$suffix"
}.getOrDefault(false)

private fun stableVersion(value: String): List<Long>? {
    if (!value.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))) return null
    return value.split('.').map { it.toLongOrNull() ?: return null }
}

/** null means the installed version is unknown; never recommend a downgrade. */
internal fun productUpdateAvailable(current: String?, latest: String): Boolean? {
    val installed = current?.removePrefix("v")?.removeSuffix("-debug") ?: return null
    val core = installed.substringBefore('-').substringBefore('+')
    val previous = stableVersion(core) ?: return null
    val next = stableVersion(latest) ?: return null
    for (index in 0..2) {
        if (next[index] != previous[index]) return next[index] > previous[index]
    }
    return installed.substringBefore('+').contains('-')
}
