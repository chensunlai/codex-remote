package dev.codexremote.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.MainSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                withTimeout(10_000) { model.state.first { it.remotePath == "/home/test" && !it.loading } }
                withContext(Dispatchers.Main) {
                    model.setSection(MainSection.TERMINAL)
                    assertEquals(MainSection.TERMINAL, model.state.value.section)
                    model.selectService("windows")
                    assertEquals(MainSection.SESSIONS, model.state.value.section)
                    model.setSection(MainSection.TERMINAL)
                    assertEquals(MainSection.SESSIONS, model.state.value.section)
                    assertThrows(IllegalStateException::class.java) { model.terminalSession("windows", "C:\\Users\\用户") }
                }
                withTimeout(10_000) { model.state.first { it.remotePath == "C:\\Users\\用户" && !it.loading } }
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
                    withTimeout(10_000) { old.directoryRequested.await() }
                    assertFalse("Gateway connection must finish while the directory is still loading", model.state.value.connecting)
                    assertTrue(model.state.value.loading)

                    withContext(Dispatchers.Main) { model.configureGateway(current.address, "fixture-token") }
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

    private class FixtureGateway(private val home: String, private val holdDirectory: Boolean, private val services: String? = null) : AutoCloseable {
        private val server = ServerSocket(0)
        private val sockets = CopyOnWriteArrayList<Socket>()
        val address = "http://127.0.0.1:${server.localPort}"
        val directoryRequested = CompletableDeferred<Unit>()
        val directoryCancelled = CompletableDeferred<Unit>()

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
                                while (!input.readLine().isNullOrEmpty()) Unit
                                val body = when (path) {
                                    "/api/v1/meta" -> """{"data":{"limits":{}}}"""
                                    "/api/v1/services" -> services?.let { "{\"data\":$it}" } ?: """{"data":[{"id":"fixture","name":"Fixture","home":"$home","runtime":{"state":"connected"}}]}"""
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
            sockets.forEach(Socket::close)
        }
    }
}
