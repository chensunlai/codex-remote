package dev.codexremote.app.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.codexremote.app.data.UiPreferences

/** Report after composition commits, so an old WebView's disposal cannot clear its replacement. */
@Composable
internal fun WebViewRetentionEffect(retained: Boolean, onChanged: (Boolean) -> Unit) {
    DisposableEffect(retained) {
        onChanged(retained)
        onDispose { if (retained) onChanged(false) }
    }
}

internal fun retainedServerPageIds(
    retained: List<String>, availableIds: Set<String>, selectedId: String?, visible: Boolean, maxPages: Int,
): List<String> {
    val selected = selectedId?.takeIf { it in availableIds }
    val valid = retained.filter { it in availableIds }
    val ordered = if (selected != null && (visible || selected in valid)) valid - selected + selected else valid
    return ordered.takeLast(maxPages.coerceIn(UiPreferences.MIN_CHAT_PAGES, UiPreferences.MAX_CHAT_PAGES))
}

/** Activity-owned pages: sockets and WebViews live until eviction, logout or Activity disposal. */
@Composable
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // Best-effort memory callbacks on older Android versions.
internal fun RetainedServerPanes(
    identity: String?,
    availableIds: Set<String>,
    selectedId: String?,
    visible: Boolean,
    maxPages: Int = UiPreferences.DEFAULT_MAX_CHAT_PAGES,
    content: @Composable (String, Boolean) -> Unit,
) {
    var retained by remember(identity) { mutableStateOf(emptyList<String>()) }
    val selected = selectedId?.takeIf { identity != null && it in availableIds }
    val pages = retainedServerPageIds(retained, availableIds, selected, visible, maxPages)
    SideEffect { if (retained != pages) retained = pages }

    val currentSelection by rememberUpdatedState(selected)
    val context = LocalContext.current.applicationContext
    DisposableEffect(context, identity) {
        fun releaseInactivePages() {
            val keep = currentSelection?.takeIf { it in retained } ?: retained.lastOrNull()
            retained = retained.filter { it == keep }
        }
        val callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = releaseInactivePages()
            override fun onTrimMemory(level: Int) {
                // UI_HIDDEN is ordinary backgrounding, not evidence of memory pressure.
                if (level in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW..ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) releaseInactivePages()
            }
        }
        context.registerComponentCallbacks(callback)
        onDispose { context.unregisterComponentCallbacks(callback) }
    }

    pages.forEach { id -> key(identity, id) {
        val active = visible && id == selected
        RetainedPane(visible = active) { content(id, active) }
    } }
}
