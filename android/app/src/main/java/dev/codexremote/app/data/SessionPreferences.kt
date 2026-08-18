package dev.codexremote.app.data

import android.content.Context
import androidx.core.content.edit
import dev.codexremote.app.model.NewSessionOptions
import org.json.JSONObject

class SessionPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("session_preferences", 0)

    fun load(scope: String, serviceId: String, cwd: String): NewSessionOptions? {
        if (scope.isBlank() || serviceId.isBlank() || !cwd.startsWith('/')) return null
        val value = preferences.getString(key(scope, serviceId), null) ?: return null
        return runCatching {
            val root = JSONObject(value)
            NewSessionOptions(
                cwd = cwd,
                model = root.optionalString("model"),
                effort = root.optionalString("effort"),
                approvalPolicy = root.optString("approvalPolicy", "on-request"),
                sandbox = root.optString("sandbox", "workspace-write"),
                networkAccess = root.optBoolean("networkAccess", true),
                permissionProfile = root.optionalString("permissionProfile"),
            )
        }.getOrNull()
    }

    fun save(scope: String, serviceId: String, value: NewSessionOptions) {
        if (scope.isBlank() || serviceId.isBlank() || !value.cwd.startsWith('/')) return
        val root = JSONObject()
            .put("approvalPolicy", value.approvalPolicy)
            .put("sandbox", value.sandbox)
            .put("networkAccess", value.networkAccess)
            .putOptional("model", value.model)
            .putOptional("effort", value.effort)
            .putOptional("permissionProfile", value.permissionProfile)
        preferences.edit { putString(key(scope, serviceId), root.toString()) }
    }

    private fun key(scope: String, serviceId: String): String = "$scope:$serviceId"
}

private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONObject.putOptional(key: String, value: String?): JSONObject {
    if (value.isNullOrBlank()) put(key, JSONObject.NULL) else put(key, value)
    return this
}
