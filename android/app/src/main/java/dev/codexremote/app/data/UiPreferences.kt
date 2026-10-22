package dev.codexremote.app.data

import android.content.Context
import androidx.core.content.edit
import dev.codexremote.app.model.AppTheme
import kotlin.math.roundToInt

class UiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val allowHttp: Boolean get() = preferences.getBoolean("allow_http", false)
    val multipleEndpoints: Boolean get() = preferences.getBoolean("multiple_endpoints", false)
    val customWindowLimit: Boolean get() = preferences.getBoolean("custom_window_limit", false)
    val customCacheLimit: Boolean get() = preferences.getBoolean("custom_cache_limit", false)
    val maxChatPages: Int get() = preferences.getInt("max_chat_pages", DEFAULT_MAX_CHAT_PAGES)
        .coerceIn(MIN_CHAT_PAGES, MAX_CHAT_PAGES)
    val resourceCacheBytes: Long get() = preferences.getLong("resource_cache_bytes", DEFAULT_RESOURCE_CACHE_BYTES)
        .takeIf { it in RESOURCE_CACHE_CHOICES } ?: DEFAULT_RESOURCE_CACHE_BYTES

    fun setCustomWindowLimit(value: Boolean) {
        preferences.edit { putBoolean("custom_window_limit", value) }
    }

    fun setCustomCacheLimit(value: Boolean) {
        preferences.edit { putBoolean("custom_cache_limit", value) }
    }

    fun setResourceCacheBytes(value: Long): Long {
        val normalized = value.takeIf { it in RESOURCE_CACHE_CHOICES } ?: DEFAULT_RESOURCE_CACHE_BYTES
        preferences.edit { putLong("resource_cache_bytes", normalized) }
        return normalized
    }

    fun setMaxChatPages(value: Int): Int {
        val normalized = value.coerceIn(MIN_CHAT_PAGES, MAX_CHAT_PAGES)
        preferences.edit { putInt("max_chat_pages", normalized) }
        return normalized
    }

    fun setExperiments(http: Boolean, multiple: Boolean) {
        preferences.edit { putBoolean("allow_http", http); putBoolean("multiple_endpoints", multiple) }
    }

    val fontScale: Float
        get() = normalize(preferences.getFloat(FONT_SCALE, DEFAULT_FONT_SCALE))

    val theme: AppTheme
        get() = AppTheme.entries.firstOrNull { it.id == preferences.getString(THEME, null) } ?: AppTheme.DARK

    fun setTheme(value: AppTheme) {
        preferences.edit { putString(THEME, value.id) }
    }

    fun setFontScale(value: Float): Float {
        val normalized = normalize(value)
        preferences.edit { putFloat(FONT_SCALE, normalized) }
        return normalized
    }

    private fun normalize(value: Float): Float =
        ((value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE) / FONT_SCALE_STEP).roundToInt() *
            FONT_SCALE_STEP)
            .coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    companion object {
        const val DEFAULT_MAX_CHAT_PAGES = 3
        const val MIN_CHAT_PAGES = 1
        const val MAX_CHAT_PAGES = 10
        const val DEFAULT_RESOURCE_CACHE_BYTES = 1024L * 1024 * 1024
        val RESOURCE_CACHE_CHOICES = listOf(128L, 256L, 512L, 1024L, 2048L).map { it * 1024 * 1024 }
        const val DEFAULT_FONT_SCALE = 1f
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.30f
        const val FONT_SCALE_STEP = 0.05f
        const val FONT_SCALE_SLIDER_STEPS = 8

        private const val PREFERENCES = "ui_preferences"
        private const val FONT_SCALE = "font_scale"
        private const val THEME = "theme"
    }
}
