package dev.codexremote.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPreferencesInstrumentedTest {
    @Test
    fun persistsAndNormalizesFontScale() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("ui_preferences", 0).edit().clear().commit()

        val preferences = UiPreferences(context)
        assertEquals(1.15f, preferences.setFontScale(1.13f), 0.001f)
        assertEquals(1.15f, UiPreferences(context).fontScale, 0.001f)
        assertEquals(1.30f, preferences.setFontScale(2f), 0.001f)
    }
}
