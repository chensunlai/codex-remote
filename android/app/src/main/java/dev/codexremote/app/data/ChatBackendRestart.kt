package dev.codexremote.app.data

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException

/** The existing Agent restart operation owns both Codex and the official extension host. */
internal suspend fun GatewayApi.restartChatBackend(serviceId: String) {
    // Send once: a lost response must never cause an automatic second restart.
    val response = restartCodex(serviceId, confirmed = true)
    var operation = response.optJSONObject("operation")
        ?: throw IOException("服务器未返回重启状态，请检查 Agent。")
    if (operation.optString("type") != "restart") throw IOException("服务器正在执行其他操作，请稍后重试。")
    val id = operation.getString("id")
    try {
        withTimeout(60_000) {
            while (true) {
                when (operation.optString("state")) {
                    "succeeded" -> return@withTimeout
                    "failed" -> throw IOException(operation.optString("error").takeIf { it.isNotBlank() } ?: "后端重启失败")
                    "running" -> Unit
                    else -> throw IOException("无法识别后端重启状态。")
                }
                delay(1_000)
                val next: JSONObject = codexRuntime(serviceId).optJSONObject("operation")
                    ?: throw IOException("服务器未返回重启状态。")
                if (next.optString("id") != id || next.optString("type") != "restart")
                    throw IOException("服务器操作已变化，请检查后端状态。")
                operation = next
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw IOException("等待重启结果超时，后端可能仍在重启；请检查连接状态。")
    }
}
