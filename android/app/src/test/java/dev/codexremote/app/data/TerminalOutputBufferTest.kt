package dev.codexremote.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalOutputBufferTest {
    @Test
    fun retainsOnlyTheNewestOutput() {
        val buffer = TerminalOutputBuffer(8)

        buffer.append("12345")
        buffer.append("67890")

        assertEquals("34567890", buffer.value)
    }

    @Test
    fun clearsOutputForAnExplicitReconnect() {
        val buffer = TerminalOutputBuffer(8)
        buffer.append("output")

        buffer.clear()

        assertEquals("", buffer.value)
    }

    @Test
    fun keepsSurrogatePairsIntactWhenTrimming() {
        val buffer = TerminalOutputBuffer(2)

        buffer.append("a😀")

        assertEquals("😀", buffer.value)
    }
}
