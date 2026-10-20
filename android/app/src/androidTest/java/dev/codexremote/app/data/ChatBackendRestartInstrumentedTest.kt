package dev.codexremote.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ChatBackendRestartInstrumentedTest {
    @Test fun confirmsOnceAndWaitsForCompletion() = runBlocking {
        RestartServer().use { server ->
            val api = server.api()
            try {
                api.restartChatBackend("windows-fixture")
                assertEquals(1, server.requests.count { it.startsWith("POST ") })
                assertTrue(server.requests.any { it.startsWith("GET /api/v2/services/windows-fixture/codex/runtime") })
                assertTrue(JSONObject(server.bodies.single()).getBoolean("confirmed"))
            } finally { api.clear() }
        }
    }

    @Test fun failedRestartIsReportedAndNeverRepeated() = runBlocking {
        RestartServer(finalState = "failed").use { server ->
            val api = server.api()
            try {
                assertEquals("fixture restart failed", runCatching { api.restartChatBackend("fixture") }.exceptionOrNull()?.message)
                assertEquals(1, server.requests.count { it.startsWith("POST ") })
            } finally { api.clear() }
        }
    }

    @Test fun concurrentUpdateIsNotMistakenForRestart() = runBlocking {
        RestartServer(type = "update").use { server ->
            val api = server.api()
            try {
                assertTrue(runCatching { api.restartChatBackend("fixture") }.isFailure)
                assertEquals(1, server.requests.size)
            } finally { api.clear() }
        }
    }

    private class RestartServer(val finalState: String = "succeeded", val type: String = "restart") : Closeable {
        private val socket = ServerSocket(0)
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        fun api() = GatewayApi().apply {
            configure(GatewayConfig("http://127.0.0.1:${socket.localPort}", "fixture", allowHttp = true))
        }
        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val peer = try { socket.accept() } catch (_: Exception) { break }
                    peer.use {
                        val reader = it.getInputStream().bufferedReader()
                        val first = reader.readLine()
                        requests += first
                        var length = 0
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        if (length > 0) {
                            val body = CharArray(length)
                            var read = 0
                            while (read < length) {
                                val count = reader.read(body, read, length - read)
                                if (count < 0) break
                                read += count
                            }
                            bodies += String(body, 0, read)
                        }
                        val state = if (first.startsWith("POST")) "running" else finalState
                        val body = """{"data":{"operation":{"id":"restart-fixture","type":"$type","state":"$state","error":"fixture restart failed"}}}""".toByteArray()
                        it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                    }
                }
            }
        }
        override fun close() = socket.close()
    }
}
