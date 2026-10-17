package dev.codexremote.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class GatewayConnectionInstrumentedTest {
    @Test
    fun cancellingConnectionClosesTheRequestBeforeHeadersArrive() = verifyCancellation(partialBody = false)

    @Test
    fun cancellingConnectionClosesTheRequestWhileReadingTheBody() = verifyCancellation(partialBody = true)

    private fun verifyCancellation(partialBody: Boolean) = runBlocking {
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val requestReceived = CompletableDeferred<Unit>()
            val peerClosed = CompletableDeferred<Unit>()
            val peer = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    if (partialBody) {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: application/json\r\n\r\n{\"data\":".toByteArray())
                            flush()
                        }
                    }
                    requestReceived.complete(Unit)
                    assertEquals("Cancellation must close the network request", -1, input.read())
                    peerClosed.complete(Unit)
                }
            }
            val attempt = launch(Dispatchers.Default) {
                GatewayApi().testConfiguration(GatewayConfig("http://127.0.0.1:${server.localPort}", "fixture-token"))
            }
            try {
                withTimeout(5_000) { requestReceived.await() }
                withTimeout(2_000) { attempt.cancelAndJoin(); peerClosed.await() }
            } finally {
                attempt.cancelAndJoin()
                peer.cancelAndJoin()
            }
        }
    }
}
