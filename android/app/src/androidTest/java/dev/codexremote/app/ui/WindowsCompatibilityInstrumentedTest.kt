package dev.codexremote.app.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import dev.codexremote.app.MainViewModel
import dev.codexremote.app.data.parseServices
import dev.codexremote.app.model.AppState
import dev.codexremote.app.model.MainSection
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class WindowsCompatibilityInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optionalCapabilitiesPreserveOldServicesAndDisableWindowsTerminal() {
        val services = parseServices(JSONArray("""[
            {"id":"linux-old","name":"Linux","platform":"linux"},
            {"id":"windows-old","name":"Windows","platform":"win32"},
            {"id":"linux-files","name":"Files","platform":"linux","capabilities":["filesystem"]},
            {"id":"windows","name":"Windows","platform":"win32","capabilities":["terminal","filesystem"]}
        ]"""))
        assertNull(services[0].capabilities)
        assertTrue(services[0].supportsTerminal)
        assertFalse(services[1].supportsTerminal)
        assertEquals(setOf("filesystem"), services[2].capabilities)
        assertFalse(services[2].supportsTerminal)
        assertFalse(services[3].supportsTerminal)
    }

    @Test fun compactAndExpandedNavigationKeepWindowsTerminalVisibleButDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ViewModelStore()
        val application = object : Application() {
            private val prefix = "windows-navigation-${UUID.randomUUID()}"
            init { attachBaseContext(context) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        lateinit var model: MainViewModel
        compose.runOnUiThread { model = MainViewModel(application).also { store.put("fixture", it) } }
        val services = parseServices(JSONArray("""[
            {"id":"windows","name":"Windows","platform":"win32","runtime":{"state":"connected"}},
            {"id":"linux","name":"Linux","platform":"linux","runtime":{"state":"connected"}}
        ]"""))
        val selected = mutableStateOf("windows")
        val expanded = mutableStateOf(false)
        try {
            compose.setContent {
                CodexRemoteTheme {
                    Box(Modifier.requiredWidth(if (expanded.value) 900.dp else 360.dp).fillMaxHeight()) {
                        CodexRemoteApp(AppState(configured = true, section = MainSection.FILES,
                            services = services, selectedServiceId = selected.value), model)
                    }
                }
            }
            listOf(false, true).forEach { wide ->
                compose.runOnIdle { expanded.value = wide; selected.value = "windows" }
                compose.onNode(hasText("终端") and isSelectable()).assertIsNotEnabled()
                compose.runOnIdle { selected.value = "linux" }
                compose.onNode(hasText("终端") and isSelectable()).assertIsEnabled()
            }
        } finally {
            compose.runOnUiThread { store.clear() }
        }
    }
}
