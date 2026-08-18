package dev.codexremote.app.data

import android.content.Context
import androidx.core.content.edit
import kotlin.math.roundToInt

class UiPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val fontScale: Float
        get() = normalize(preferences.getFloat(FONT_SCALE, DEFAULT_FONT_SCALE))

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
        const val DEFAULT_FONT_SCALE = 1f
        const val MIN_FONT_SCALE = 0.85f
        const val MAX_FONT_SCALE = 1.30f
        const val FONT_SCALE_STEP = 0.05f
        const val FONT_SCALE_SLIDER_STEPS = 8

        private const val PREFERENCES = "ui_preferences"
        private const val FONT_SCALE = "font_scale"
    }
}
