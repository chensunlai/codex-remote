package dev.codexremote.app.data

import androidx.test.platform.app.InstrumentationRegistry
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.RemoteService
import dev.codexremote.app.model.RuntimeState
import dev.codexremote.app.model.WorkspaceWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkspaceWindowStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = GatewayConfig("https://window-store-test.invalid", "test-owner")

    @Before @After fun clearPreferences() {
        listOf("workspace-windows-v1", "official-frontends", "extension-webview-state").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    @Test fun windowsOrderSelectionAndDeliberatelyEmptyListSurviveReload() {
        val first = WorkspaceWindow("8b630625-6e07-4636-9642-cf8679d3d915", "linux", "/project", "First")
        val second = WorkspaceWindow("03dde33c-a3fd-411b-900d-302456a75697", "windows", "C:\\中文 项目", "Second")
        WorkspaceWindowStore(context).save(config, listOf(second, first), first.id)
        val restored = WorkspaceWindowStore(context).load(config)!!
        assertEquals(listOf(second, first), restored.windows)
        assertEquals(first.id, restored.selectedId)
        assertNull(WorkspaceWindowStore(context).load(config.copy(token = "other-owner")))
        WorkspaceWindowStore(context).save(config, emptyList(), null)
        assertNotNull(WorkspaceWindowStore(context).load(config))
        assertTrue(WorkspaceWindowStore(context).load(config)!!.windows.isEmpty())
    }

    @Test fun migrationReusesClientDirectoryAndOpaqueStateWithoutSharingNewWindows() {
        val service = RemoteService("server", "Server", null, "linux", null, null, "/home/user",
            RuntimeState.CONNECTED, null)
        val scope = frontendScope(config, service.id)
        val clientId = "0ebf7a5e-de83-4811-88d4-d907ddf9e0af"
        context.getSharedPreferences("official-frontends", 0).edit()
            .putString("client-$scope", clientId).putString("workspace-$scope", "/old directory").commit()
        val opaque = "{\"draft\":\"中文😀\",\"opaque\":[1,true,null]}"
        val store = OfficialFrontendStore(context)
        store.setState(scope, "existing-view:main", opaque)
        store.setState(scope, "existing-view:settings", "[\"opaque auxiliary state\"]")
        val migrated = store.legacyWindow(config, service)!!
        assertEquals(clientId, migrated.id)
        assertEquals("/old directory", migrated.workspace)
        val target = windowStateScope(config, migrated)
        assertEquals(opaque, store.state(target, "existing-view:main"))
        assertEquals("[\"opaque auxiliary state\"]", store.state(target, "existing-view:settings"))
        assertEquals("null", store.state(windowStateScope(config, migrated.copy(id = "f9afac43-570e-4ff1-82db-72fa06614ed1")), "existing-view:main"))
        store.setState(target, "existing-view:main", "{\"new\":true}")
        store.legacyWindow(config, service)
        assertEquals("{\"new\":true}", store.state(target, "existing-view:main"))
    }
}
