package dev.codexremote.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.data.OfficialFrontendStore
import dev.codexremote.app.data.SecretStore
import dev.codexremote.app.data.UiPreferences
import dev.codexremote.app.data.WorkspaceWindowStore
import dev.codexremote.app.data.frontendScope
import dev.codexremote.app.data.windowStateScope
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.model.MainSection
import dev.codexremote.app.model.WorkspaceWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class MainViewModelGatewayInstrumentedTest {
    @Test
    fun coldStartKeepsSavedWindowsUnopenedUntilSelectedAndPreservesLegacyMode() = runBlocking {
        for (windowMode in listOf(true, false)) {
            val application = IsolatedApplication(ApplicationProvider.getApplicationContext())
            val store = ViewModelStore()
            FixtureGateway("/home/fixture", false).use { gateway ->
                val config = GatewayConfig(gateway.address, "fixture-token", allowHttp = true)
                val first = WorkspaceWindow(UUID.randomUUID().toString(), "fixture", "/first", "First")
                val second = WorkspaceWindow(UUID.randomUUID().toString(), "fixture", "/second", "Second")
                val windows = listOf(first, second)
                UiPreferences(application).apply { setExperiments(true, false); setWindowMode(windowMode) }
                SecretStore(application).save(config)
                WorkspaceWindowStore(application).save(config, windows, second.id)
                val model = withContext(Dispatchers.Main) {
                    MainViewModel(application).also { store.put("fixture", it) }
                }
                try {
                    val initial = withTimeout(10_000) { model.state.first { it.services.size == 1 && !it.loading } }
                    assertEquals(windows, initial.windows)
                    assertEquals(listOf(second.id, first.id), initial.recentWindowIds)
                    assertEquals(if (windowMode) null else second.id, initial.selectedWindowId)
                    assertEquals(MainSection.SESSIONS, initial.section)
                    assertTrue(gateway.requestPaths.all { it == "/api/v1/meta" || it == "/api/v1/services" })
                    withContext(Dispatchers.Main) { model.refreshServices() }
                    withTimeout(10_000) { model.state.first { !it.loading } }
                    assertEquals(initial.selectedWindowId, model.state.value.selectedWindowId)

                    withContext(Dispatchers.Main) {
                        model.selectWindow(first.id)
                        model.setSection(MainSection.SERVICES)
                        model.setSection(MainSection.SESSIONS)
                        model.setForeground(true)
                        model.setForeground(false)
                        model.refreshServices()
                    }
                    val resumed = withTimeout(10_000) { model.state.first { !it.loading } }
                    assertEquals(first.id, resumed.selectedWindowId)
                    assertEquals(windows, resumed.windows)
                    assertEquals(first.id, WorkspaceWindowStore(application).load(config)!!.selectedId)
                } finally {
                    withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
                }
            }
        }
    }

    @Test
    fun newWindowModeGatewayKeepsWelcomeForEmptyOfflineAndOnlineServers() = runBlocking {
        val variants = listOf("[]", "disconnected", "connected").map { state ->
            if (state == "[]") state else
                """[{"id":"fixture","name":"Fixture","platform":"linux","runtime":{"state":"$state"}}]"""
        }
        for (services in variants) {
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                MainViewModel(IsolatedApplication(ApplicationProvider.getApplicationContext())).also { store.put("fixture", it) }
            }
            try {
                FixtureGateway("/home/fixture", false, services).use { gateway ->
                    withContext(Dispatchers.Main) {
                        model.setExperiments(http = true, multiple = false)
                        model.configureGateway(gateway.address, "fixture-token")
                    }
                    val state = withTimeout(10_000) { model.state.first { it.configured && !it.loading && !it.connecting } }
                    assertTrue(state.windows.isEmpty())
                    assertNull(state.selectedWindowId)
                    assertEquals(MainSection.SESSIONS, state.section)
                    assertTrue(gateway.requestPaths.all { it == "/api/v1/meta" || it == "/api/v1/services" })
                }
            } finally {
                withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
            }
        }
    }

    @Test
    fun migratedLegacyWindowPreservesIdentityAndDraftWithoutOpening() = runBlocking {
        val application = IsolatedApplication(ApplicationProvider.getApplicationContext())
        val store = ViewModelStore()
        FixtureGateway("/home/fixture", false).use { gateway ->
            val config = GatewayConfig(gateway.address, "fixture-token", allowHttp = true)
            val id = UUID.randomUUID().toString()
            val scope = frontendScope(config, "fixture")
            UiPreferences(application).setExperiments(true, false)
            SecretStore(application).save(config)
            application.getSharedPreferences("official-frontends", 0).edit()
                .putString("client-$scope", id).putString("workspace-$scope", "/existing").commit()
            val frontends = OfficialFrontendStore(application)
            val opaque = """{"draft":"保留草稿"}"""
            frontends.setState(scope, "existing:main", opaque)
            val model = withContext(Dispatchers.Main) {
                MainViewModel(application).also { store.put("fixture", it) }
            }
            try {
                val state = withTimeout(10_000) { model.state.first { it.services.size == 1 && !it.loading } }
                val window = state.windows.single()
                assertEquals(id, window.id)
                assertEquals("/existing", window.workspace)
                assertNull(state.selectedWindowId)
                assertEquals(opaque, frontends.state(windowStateScope(config, window), "existing:main"))
                assertEquals(listOf(window), WorkspaceWindowStore(application).load(config)!!.windows)
            } finally {
                withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
            }
        }
    }

    @Test
    fun offlineServersDoNotAutomaticallyOrManuallyCreateWindows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            MainViewModel(IsolatedApplication(context)).also { store.put("fixture", it) }
        }
        val services = """[{"id":"fixture","name":"Fixture","platform":"linux","home":"/home/fixture","runtime":{"state":"disconnected"}}]"""
        try {
            FixtureGateway("/home/fixture", false, services).use { gateway ->
                withContext(Dispatchers.Main) {
                    model.setExperiments(http = true, multiple = false)
                    model.configureGateway(gateway.address, "fixture-token")
                }
                withTimeout(10_000) { model.state.first { it.services.size == 1 && !it.loading } }
                assertTrue(model.state.value.windows.isEmpty())
                withContext(Dispatchers.Main) { model.createWindow("fixture", "/home/fixture") }
                assertTrue(model.state.value.windows.isEmpty())
                assertEquals("服务器离线，连接恢复后再创建窗口", model.state.value.error)
            }
        } finally {
            withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
        }
    }

    @Test
    fun lateWindowHomeResponseCannotUndoSectionOrServerNavigation() = runBlocking {
        for (changeSection in listOf(true, false)) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                MainViewModel(IsolatedApplication(context)).also { store.put("fixture", it) }
            }
            val services = JSONArray().apply {
                listOf("fixture", "delayed", "other").forEach { id ->
                    put(JSONObject().put("id", id).put("name", id).put("platform", "linux")
                        .apply { if (id != "delayed") put("home", "/home/$id") }
                        .put("runtime", JSONObject().put("state", "connected")))
                }
            }
            try {
                FixtureGateway("/home/fixture", false, services.toString(), holdHome = true).use { gateway ->
                    withContext(Dispatchers.Main) {
                        model.setExperiments(http = true, multiple = false)
                        model.configureGateway(gateway.address, "fixture-token")
                    }
                    withTimeout(10_000) { model.state.first { it.services.size == 3 && !it.loading } }
                    withContext(Dispatchers.Main) { model.openServiceWindow("fixture") }
                    val original = model.state.value.windows.single()
                    withContext(Dispatchers.Main) { model.openServiceWindow("delayed") }
                    withTimeout(10_000) { gateway.homeRequested.await() }
                    withContext(Dispatchers.Main) {
                        if (changeSection) model.setSection(MainSection.FILES) else model.selectService("other")
                    }
                    gateway.releaseHome.complete(Unit)
                    val completed = withTimeout(10_000) { model.state.first { !it.loading } }
                    assertEquals(listOf(original), completed.windows)
                    assertEquals(original.id, completed.selectedWindowId)
                    assertEquals(if (changeSection) MainSection.FILES else MainSection.SESSIONS, completed.section)
                    assertEquals(if (changeSection) "fixture" else "other", completed.selectedServiceId)
                }
            } finally {
                withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
            }
        }
    }

    @Test
    fun serverModeReusesSeparateToolWindowsAndChangingModePreservesThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = IsolatedApplication(context)
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            MainViewModel(application).also { store.put("fixture", it) }
        }
        val services = JSONArray().apply {
            listOf("fixture", "second", "windows").forEach { id ->
                put(JSONObject().put("id", id).put("name", id)
                    .put("platform", if (id == "windows") "win32" else "linux")
                    .put("home", if (id == "windows") "C:\\Users\\User" else "/home/$id")
                    .put("runtime", JSONObject().put("state", "connected")))
            }
        }
        try {
            FixtureGateway("/home/fixture", false, services.toString()).use { gateway ->
                withContext(Dispatchers.Main) {
                    model.setExperiments(http = true, multiple = false)
                    model.configureGateway(gateway.address, "fixture-token")
                }
                withTimeout(10_000) { model.state.first { it.services.size == 3 && !it.loading } }
                withContext(Dispatchers.Main) {
                    model.setWindowMode(false)
                    model.openServiceWindow("fixture")
                    assertFalse(model.state.value.windowMode)
                    val original = model.state.value.windows.single()
                    model.switchWindowTool()
                    val claudeId = requireNotNull(model.state.value.selectedWindowId)
                    assertEquals("claude", model.state.value.windows.last().toolId)
                    assertEquals("fixture", model.state.value.windows.last().title)
                    assertEquals(original.workspace, model.state.value.windows.last().workspace)
                    model.switchWindowTool()
                    assertEquals(original.id, model.state.value.selectedWindowId)
                    model.createWindow("fixture", "/another", "Additional")
                    model.selectWindow(original.id)
                    model.switchWindowTool()
                    assertEquals(claudeId, model.state.value.selectedWindowId)
                    model.switchWindowTool()
                    assertEquals(original.id, model.state.value.selectedWindowId)
                    assertEquals(3, model.state.value.windows.size)

                    model.selectWindow(claudeId)
                    model.openServiceWindow("second")
                    val secondClaude = model.state.value.windows.last()
                    assertEquals("claude", secondClaude.toolId)
                    model.openServiceWindow("fixture")
                    assertEquals(claudeId, model.state.value.selectedWindowId)
                    model.openServiceWindow("second")
                    assertEquals(secondClaude.id, model.state.value.selectedWindowId)
                    model.openServiceWindow("windows")
                    assertEquals("codex", model.state.value.windows.last().toolId)

                    model.selectWindow(claudeId)
                    val before = model.state.value
                    model.setWindowMode(true)
                    model.setWindowMode(false)
                    assertEquals(before, model.state.value)

                    model.setSection(MainSection.TERMINAL)
                    model.selectService("second")
                    assertEquals(claudeId, model.state.value.selectedWindowId)
                    model.setSection(MainSection.SESSIONS)
                    assertEquals("fixture", model.state.value.selectedServiceId)
                    assertEquals(claudeId, model.state.value.selectedWindowId)
                    assertEquals(5, model.state.value.windows.size)
                    model.createWindow("fixture", "/second-claude", toolId = "claude")
                    val another = model.state.value.windows.last()
                    assertEquals("fixture 2", another.title)
                    model.renameWindow(another.id, "fixture")
                    assertEquals(2, model.state.value.windows.count { it.title == "fixture" && it.toolId == "claude" })
                    assertEquals(another.id, model.state.value.selectedWindowId)
                }
                val restored = dev.codexremote.app.data.WorkspaceWindowStore(application).load(model.state.value.gatewayConfig!!)!!
                assertEquals(model.state.value.windows, restored.windows)
                assertEquals(model.state.value.selectedWindowId, restored.selectedId)
                assertEquals(model.state.value.recentWindowIds, restored.recentIds)
            }
        } finally {
            withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
        }
    }

    @Test
    fun switchingToWindowsLeavesTerminalAndDirectNavigationCannotReopenIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            MainViewModel(IsolatedApplication(context)).also { store.put("fixture", it) }
        }
        val services = JSONArray().apply {
            put(JSONObject().put("id", "fixture").put("name", "Linux").put("platform", "linux").put("home", "/home/test")
                .put("runtime", JSONObject().put("state", "connected")))
            put(JSONObject().put("id", "windows").put("name", "Windows").put("platform", "win32").put("home", "C:\\Users\\用户")
                .put("capabilities", JSONArray().put("filesystem")).put("runtime", JSONObject().put("state", "connected")))
        }
        try {
            FixtureGateway("/home/test", false, services.toString()).use { gateway ->
                withContext(Dispatchers.Main) {
                    model.setExperiments(http = true, multiple = false)
                    model.configureGateway(gateway.address, "fixture-token")
                }
                withTimeout(10_000) { model.state.first { it.services.size == 2 && !it.loading } }
                withContext(Dispatchers.Main) {
                    model.setSection(MainSection.TERMINAL)
                    assertEquals(MainSection.TERMINAL, model.state.value.section)
                    model.selectService("windows")
                    assertEquals(MainSection.SESSIONS, model.state.value.section)
                    model.setSection(MainSection.TERMINAL)
                    assertEquals(MainSection.SESSIONS, model.state.value.section)
                    assertThrows(IllegalStateException::class.java) { model.terminalSession("windows", "C:\\Users\\用户") }
                }
                withContext(Dispatchers.Main) {
                    model.selectService("fixture")
                    model.setSection(MainSection.TERMINAL)
                    assertEquals(MainSection.TERMINAL, model.state.value.section)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
        }
    }

    @Test
    fun connectingFinishesBeforeDirectoryLoadingAndSwitchingCancelsTheOldDirectory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            MainViewModel(IsolatedApplication(context)).also { store.put("fixture", it) }
        }
        try {
            FixtureGateway("/old", holdDirectory = true).use { old ->
                FixtureGateway("/new", holdDirectory = false).use { current ->
                    withContext(Dispatchers.Main) {
                        model.setExperiments(http = true, multiple = false)
                        model.configureGateway(old.address, "fixture-token")
                    }
                    withTimeout(10_000) { model.state.first { it.services.size == 1 && !it.loading } }
                    withContext(Dispatchers.Main) { model.setSection(MainSection.FILES) }
                    withTimeout(10_000) { old.directoryRequested.await() }
                    assertFalse("Gateway connection must finish while the directory is still loading", model.state.value.connecting)
                    assertTrue(model.state.value.loading)

                    withContext(Dispatchers.Main) { model.configureGateway(current.address, "fixture-token") }
                    withTimeout(10_000) { model.state.first { it.gatewayConfig?.baseUrl == current.address && !it.loading } }
                    withContext(Dispatchers.Main) { model.setSection(MainSection.FILES) }
                    val state = withTimeout(10_000) {
                        model.state.first { it.gatewayConfig?.baseUrl == current.address && it.remotePath == "/new" && !it.loading }
                    }
                    withTimeout(2_000) { old.directoryCancelled.await() }
                    assertFalse(state.connecting)
                    assertEquals("/new", state.remotePath)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { model.clearGateway(); store.clear() }
        }
    }

    private class IsolatedApplication(context: Context) : Application() {
        private val prefix = "gateway-fixture-${UUID.randomUUID()}"
        init { attachBaseContext(context) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$prefix-$name", mode)
    }

    private class FixtureGateway(private val home: String, private val holdDirectory: Boolean, private val services: String? = null,
        private val holdHome: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        val address = "http://127.0.0.1:${server.localPort}"
        val directoryRequested = CompletableDeferred<Unit>()
        val directoryCancelled = CompletableDeferred<Unit>()
        val homeRequested = CompletableDeferred<Unit>()
        val releaseHome = CompletableDeferred<Unit>()
        val requestPaths = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true, name = "gateway-fixture") {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    sockets += socket
                    thread(isDaemon = true, name = "gateway-fixture-request") request@{
                        try {
                            socket.use {
                                val input = it.getInputStream().bufferedReader()
                                val path = input.readLine().split(' ')[1].substringBefore('?')
                                requestPaths += path
                                while (!input.readLine().isNullOrEmpty()) Unit
                                val body = when (path) {
                                    "/api/v1/meta" -> """{"data":{"limits":{}}}"""
                                    "/api/v1/services" -> services?.let { "{\"data\":$it}" } ?: """{"data":[{"id":"fixture","name":"Fixture","home":"$home","runtime":{"state":"connected"}}]}"""
                                    "/api/v1/services/delayed/fs/home" -> {
                                        homeRequested.complete(Unit)
                                        if (holdHome) runBlocking { releaseHome.await() }
                                        """{"data":{"path":"/home/delayed"}}"""
                                    }
                                    "/api/v1/services/fixture/fs/list", "/api/v1/services/windows/fs/list" -> {
                                        directoryRequested.complete(Unit)
                                        if (holdDirectory) {
                                            input.read()
                                            directoryCancelled.complete(Unit)
                                            return@request
                                        }
                                        """{"data":[]}"""
                                    }
                                    else -> error("Unexpected fixture path: $path")
                                }
                                val bytes = body.toByteArray()
                                it.getOutputStream().apply {
                                    write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                                    write(bytes)
                                    flush()
                                }
                            }
                        } catch (error: Exception) {
                            if (!server.isClosed) {
                                directoryRequested.completeExceptionally(error)
                                directoryCancelled.completeExceptionally(error)
                                homeRequested.completeExceptionally(error)
                            }
                        } finally {
                            sockets -= socket
                        }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            releaseHome.complete(Unit)
            sockets.forEach(Socket::close)
        }
    }
}
