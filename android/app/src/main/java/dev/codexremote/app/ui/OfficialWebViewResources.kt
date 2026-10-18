package dev.codexremote.app.ui

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse

/** Resolve host resources separately from the official extension's versioned assets. */
internal fun officialWebViewResource(
    url: Uri,
    originHost: String?,
    load: (version: String, path: String) -> ByteArray,
    onFailure: (Throwable) -> Unit,
): WebResourceResponse? {
    if (url.host != originHost) return null
    if (url.path == "/favicon.ico") {
        return WebResourceResponse("image/x-icon", null, 204, "No Content", emptyMap(), ByteArray(0).inputStream())
    }
    return runCatching {
        val segments = url.pathSegments
        require(segments.size >= 3 && segments.first() == "extension") { "资源路径无效" }
        val version = segments[1]
        val path = segments.drop(2).joinToString("/")
        val mime = when {
            path.endsWith(".mjs") || path.endsWith(".js") -> "text/javascript"
            path.endsWith(".wasm") -> "application/wasm"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.')) ?: "application/octet-stream"
        }
        WebResourceResponse(mime, "UTF-8", 200, "OK",
            mapOf("Cache-Control" to "private, max-age=31536000, immutable"), load(version, path).inputStream())
    }.getOrElse { failure ->
        onFailure(failure)
        WebResourceResponse("text/plain", "UTF-8", 502, "Resource unavailable", emptyMap(), ByteArray(0).inputStream())
    }
}
