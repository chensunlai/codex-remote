package dev.codexremote.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class GatewayRoutingInstrumentedTest {
    @Test fun httpRequiresOptInAndTurningItOffStopsRequests() = runBlocking {
        ProbeServer().use { server ->
            val config = GatewayConfig(server.url, "test-token")
            val api = GatewayApi()
            try {
                assertTrue(runCatching { api.testConfiguration(config) }.isFailure)
                assertEquals(0, server.requests.size)
                api.configure(config.copy(allowHttp = true))
                api.meta()
                assertEquals(1, server.requests.size)
                api.updatePolicy(config)
                assertTrue(runCatching { api.meta() }.isFailure)
                assertEquals(1, server.requests.size)
            } finally { api.clear() }
        }
    }

    @Test fun failoverIsOrderedAndStableUntilAnExplicitPriorityCheck() = runBlocking {
        ProbeServer().use { preferred -> ProbeServer().use { fallback ->
            preferred.status = 503
            val config = GatewayConfig(fallback.url, "test-token", allowHttp = true, multipleEndpoints = true,
                endpoints = listOf(preferred.url))
            val api = GatewayApi()
            try {
                val initial = api.selectConfiguration(config)
                assertEquals(fallback.url, initial.endpoint)
                api.configure(config, initial.endpoint)
                preferred.status = 200
                val before = preferred.requests.size
                api.checkConnection()
                assertEquals("Periodic latency probes must not interrupt a working route", before, preferred.requests.size)
                assertEquals(fallback.url, api.link.value.endpoint)
                assertNotNull(api.link.value.latencyMs)
                api.checkConnection(preferPrimary = true, reconnect = true)
                assertEquals(preferred.url, api.link.value.endpoint)
                api.meta()
                assertTrue(preferred.requests.all { it.contains("Authorization: Bearer test-token", true) })
                preferred.status = 503
                api.checkConnection()
                assertEquals(fallback.url, api.link.value.endpoint)
            } finally { api.clear() }
        } }
    }

    @Test fun rejectedAuthenticationDoesNotCountAsAReachableGateway() = runBlocking {
        ProbeServer().use { preferred -> ProbeServer().use { fallback ->
            preferred.status = 401
            val config = GatewayConfig(fallback.url, "test-token", allowHttp = true, multipleEndpoints = true,
                endpoints = listOf(preferred.url))
            val api = GatewayApi()
            try { assertEquals(fallback.url, api.selectConfiguration(config).endpoint) }
            finally { api.clear() }
        } }
    }

    @Test fun redirectsCannotSendCredentialsToAnotherAddress() = runBlocking {
        ProbeServer().use { source -> ProbeServer().use { target ->
            source.status = 302
            source.location = target.url
            val api = GatewayApi()
            try {
                assertTrue(runCatching { api.testConfiguration(GatewayConfig(source.url, "test-token", allowHttp = true)) }.isFailure)
                assertEquals(0, target.requests.size)
            } finally { api.clear() }
        } }
    }

    @Test fun failedBusinessRequestsAreNotRetriedOnOtherEntrances() = runBlocking {
        ProbeServer().use { first -> ProbeServer().use { fallback ->
            val config = GatewayConfig(first.url, "test-token", allowHttp = true, multipleEndpoints = true,
                endpoints = listOf(first.url, fallback.url))
            val api = GatewayApi()
            try {
                api.configure(config)
                first.status = 503
                assertTrue(runCatching { api.testService("service") }.isFailure)
                assertEquals(1, first.requests.size)
                assertEquals(0, fallback.requests.size)
            } finally { api.clear() }
        } }
    }

    @Test fun disablingMultipleEntrancesReturnsToPrimaryAndFailureIsRetryableFromUi() = runBlocking {
        ProbeServer().use { preferred -> ProbeServer().use { primary ->
            val config = GatewayConfig(primary.url, "test-token", allowHttp = true, multipleEndpoints = true,
                endpoints = listOf(preferred.url))
            val api = GatewayApi()
            try {
                api.configure(config)
                api.checkConnection(preferPrimary = true)
                assertEquals(preferred.url, api.link.value.endpoint)
                api.updatePolicy(config.copy(multipleEndpoints = false))
                api.checkConnection()
                assertEquals(primary.url, api.link.value.endpoint)
                primary.status = 503
                assertTrue(runCatching { api.checkConnection(reconnect = true) }.isFailure)
                assertFalse(api.link.value.checking)
                assertNull(api.link.value.latencyMs)
            } finally { api.clear() }
        } }
    }

    private class ProbeServer : Closeable {
        private val server = ServerSocket(0)
        val url = "http://127.0.0.1:${server.localPort}"
        val requests = CopyOnWriteArrayList<String>()
        @Volatile var status = 200
        @Volatile var location: String? = null
        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; lines += line }
                        requests += lines.joinToString("\n")
                        val body = """{"data":{"version":"fixture","auth":{"tokenId":"test"}}}""".toByteArray()
                        val redirect = location?.let { target -> "Location: $target\r\n" }.orEmpty()
                        it.getOutputStream().write(("HTTP/1.1 $status Fixture\r\nContent-Length: ${body.size}\r\nConnection: close\r\n${redirect}\r\n").toByteArray() + body)
                    }
                }
            }
        }
        override fun close() { server.close() }
    }
}
