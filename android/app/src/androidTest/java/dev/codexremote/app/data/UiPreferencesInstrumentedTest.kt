package dev.codexremote.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UiPreferencesInstrumentedTest {
    @Test
    fun persistsAndNormalizesFontScale() {
        val context = isolatedContext()

        val preferences = UiPreferences(context)
        assertEquals(1.15f, preferences.setFontScale(1.13f), 0.001f)
        assertEquals(1.15f, UiPreferences(context).fontScale, 0.001f)
        assertEquals(1.30f, preferences.setFontScale(2f), 0.001f)
    }

    @Test
    fun defaultsToDarkAndPersistsEachExplicitTheme() {
        val context = isolatedContext()
        assertEquals(AppTheme.DARK, UiPreferences(context).theme)
        AppTheme.entries.forEach { theme ->
            UiPreferences(context).setTheme(theme)
            assertEquals(theme, UiPreferences(context).theme)
        }
    }

    private fun isolatedContext(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
        private val prefix = "ui-fixture-${UUID.randomUUID()}"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$prefix-$name", mode)
    }
}
