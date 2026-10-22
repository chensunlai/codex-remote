package dev.codexremote.app.data

import android.content.Context
import androidx.core.content.edit
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.WorkspaceWindow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SavedWorkspaceWindows(val windows: List<WorkspaceWindow>, val selectedId: String?)

/** Window order and selection belong to a Gateway credential scope, not a selected server. */
internal class WorkspaceWindowStore(context: Context) {
    private val preferences = context.getSharedPreferences("workspace-windows-v1", Context.MODE_PRIVATE)

    fun load(config: GatewayConfig): SavedWorkspaceWindows? {
        val key = frontendScope(config, "")
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val root = JSONObject(encoded)
            val entries = root.getJSONArray("windows")
            val seen = mutableSetOf<String>()
            val windows = (0 until entries.length()).mapNotNull { index ->
                runCatching {
                    val entry = entries.getJSONObject(index)
                    val id = entry.getString("id")
                    require(UUID.fromString(id).toString() == id && seen.add(id))
                    val path = entry.getString("workspace")
                    require(isRemoteAbsolutePath(path))
                    WorkspaceWindow(id, entry.getString("serviceId"), path, entry.getString("title"),
                        entry.optString("toolId", "codex"))
                }.getOrNull()
            }
            val selected = root.optString("selectedId").takeIf { id -> windows.any { it.id == id } }
                ?: windows.firstOrNull()?.id
            SavedWorkspaceWindows(windows, selected)
        }.getOrNull()
    }

    fun save(config: GatewayConfig, windows: List<WorkspaceWindow>, selectedId: String?) {
        val entries = JSONArray()
        windows.forEach { window ->
            entries.put(JSONObject().put("id", window.id).put("serviceId", window.serviceId)
                .put("workspace", window.workspace).put("title", window.title).put("toolId", window.toolId))
        }
        val encoded = JSONObject().put("windows", entries).put("selectedId", selectedId ?: JSONObject.NULL).toString()
        preferences.edit { putString(frontendScope(config, ""), encoded) }
    }
}
