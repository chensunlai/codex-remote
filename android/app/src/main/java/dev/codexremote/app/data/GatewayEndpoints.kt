package dev.codexremote.app.data

import dev.codexremote.app.model.GatewayConfig
import okhttp3.HttpUrl.Companion.toHttpUrl

/** The primary address is a stable identity, never the currently selected network route. */
internal fun gatewayEndpoints(config: GatewayConfig): List<String> =
    (if (config.multipleEndpoints) config.endpoints + config.baseUrl else listOf(config.baseUrl))
        .map { normalizeGatewayUrl(it) }.distinct()
        .filter { config.allowHttp || it.startsWith("https://") }
        .also { require(it.isNotEmpty()) { "HTTP 接入需要先在实验性功能中开启" } }

internal fun normalizeGatewayUrl(value: String): String {
    val input = value.trim().let { if (it.contains("://")) it else "https://$it" }
    val url = input.toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Gateway 地址不应包含账号、查询参数或片段"
    }
    return url.toString().trimEnd('/')
}

data class GatewayLinkState(
    val endpoint: String? = null,
    val latencyMs: Long? = null,
    val checking: Boolean = true,
    val error: String? = null,
)
