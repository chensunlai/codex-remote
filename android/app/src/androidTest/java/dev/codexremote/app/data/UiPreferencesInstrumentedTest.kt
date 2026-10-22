package dev.codexremote.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UiPreferencesInstrumentedTest {
    @Test fun persistsAndBoundsChatPageLimit() {
        val context = isolatedContext()
        val preferences = UiPreferences(context)
        assertEquals(3, preferences.maxChatPages)
        assertEquals(8, preferences.setMaxChatPages(8))
        assertEquals(8, UiPreferences(context).maxChatPages)
        assertEquals(1, preferences.setMaxChatPages(0))
        assertEquals(10, preferences.setMaxChatPages(100))
    }

    @Test fun tuningSwitchesPersistIndependentlyWithoutResettingActiveValues() {
        val context = isolatedContext()
        val preferences = UiPreferences(context)
        assertFalse(preferences.customWindowLimit)
        assertFalse(preferences.customCacheLimit)
        assertEquals(1024L * 1024 * 1024, preferences.resourceCacheBytes)
        preferences.setCustomWindowLimit(true)
        preferences.setCustomCacheLimit(true)
        preferences.setMaxChatPages(7)
        preferences.setResourceCacheBytes(2L * 1024 * 1024 * 1024)
        assertTrue(UiPreferences(context).customWindowLimit)
        assertTrue(UiPreferences(context).customCacheLimit)
        preferences.setCustomWindowLimit(false)
        preferences.setCustomCacheLimit(false)
        val reopened = UiPreferences(context)
        assertFalse(reopened.customWindowLimit)
        assertFalse(reopened.customCacheLimit)
        assertEquals(7, reopened.maxChatPages)
        assertEquals(2L * 1024 * 1024 * 1024, reopened.resourceCacheBytes)
        UiPreferences.RESOURCE_CACHE_CHOICES.forEach { value ->
            preferences.setResourceCacheBytes(value)
            assertEquals(value, UiPreferences(context).resourceCacheBytes)
        }
        assertEquals(UiPreferences.DEFAULT_RESOURCE_CACHE_BYTES, preferences.setResourceCacheBytes(-1))
    }

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
