package dev.codexremote.app.data

import android.content.Context
import androidx.core.content.edit
import dev.codexremote.app.model.GatewayConfig
import java.security.MessageDigest
import java.util.UUID

/** Only host preferences live here. The Agent owns the complete official plugin package. */
class OfficialFrontendStore(context: Context) {
    // Retain the preference name so workspace and client identities survive the migration.
    private val preferences = context.getSharedPreferences("official-frontends", Context.MODE_PRIVATE)
    private val pageState = context.getSharedPreferences("extension-webview-state", Context.MODE_PRIVATE)

    fun workspace(scope: String, home: String): String = preferences.getString("workspace-$scope", home) ?: home

    fun setWorkspace(scope: String, path: String) {
        require(path.startsWith('/')) { "工作目录必须为远端绝对路径" }
        preferences.edit { putString("workspace-$scope", path) }
    }

    fun clientId(scope: String): String = preferences.getString("client-$scope", null)
        ?: UUID.randomUUID().toString().also { id -> preferences.edit { putString("client-$scope", id) } }

    /** Public WebView getState/setState values are opaque JSON; the host never reads their fields. */
    internal fun state(scope: String, viewId: String): String = pageState.getString("$scope:$viewId", "null") ?: "null"

    internal fun setState(scope: String, viewId: String, value: String) {
        pageState.edit { putString("$scope:$viewId", value) }
    }
}

fun frontendScope(config: GatewayConfig, serviceId: String): String =
    MessageDigest.getInstance("SHA-256").digest("${config.baseUrl}\u0000${config.token}\u0000$serviceId".toByteArray())
        .joinToString("") { "%02x".format(it) }
