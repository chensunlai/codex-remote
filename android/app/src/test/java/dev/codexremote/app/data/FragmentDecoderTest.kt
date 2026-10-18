package dev.codexremote.app.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties

class FragmentDecoderTest {
    @Test fun sharedUnicodeVectorPreservesBytesAcrossMultibyteBoundaries() {
        val fixture = Properties().apply { FragmentDecoderTest::class.java.getResourceAsStream("/unicode.properties")!!.reader(Charsets.UTF_8).use(::load) }
        val value = fixture.getProperty("seed").repeat(fixture.getProperty("repeat").toInt())
        val bytes = value.toByteArray(Charsets.UTF_8)
        assertEquals(fixture.getProperty("bytes").toInt(), bytes.size)
        val decoder = FragmentDecoder()
        var result: String? = null
        var offset = 0
        while (offset < bytes.size) {
            val part = part(fixture.getProperty("transferId").toInt(), offset, bytes.size, bytes.copyOfRange(offset, minOf(offset + 1024 * 1024, bytes.size)))
            if (offset == 0) assertEquals(fixture.getProperty("firstHeaderHex"), hex(part.copyOfRange(0, 16)))
            result = decoder.binary(part)
            offset += part.size - 16
        }
        assertEquals(value, result)
        assertEquals(fixture.getProperty("sha256"), hex(MessageDigest.getInstance("SHA-256").digest(result!!.toByteArray())))
        assertFalse(decoder.active)
        assertEquals("next", decoder.text("next"))
    }

    @Test fun invalidOffsetAndInterleavingAreRejected() {
        val decoder = FragmentDecoder()
        assertNull(decoder.binary(part(1, 0, TRANSPORT_CHUNK_BYTES + 1, ByteArray(100))))
        assertThrows(IllegalArgumentException::class.java) { decoder.text("interleaved") }
        assertThrows(IllegalArgumentException::class.java) { decoder.binary(part(1, 99, TRANSPORT_CHUNK_BYTES + 1, byteArrayOf(1))) }
        decoder.clear()
        assertEquals("reset", decoder.text("reset"))
    }

    @Test fun configuredLimitIsCheckedBeforeAllocation() {
        val decoder = FragmentDecoder(64 * 1024 * 1024)
        assertThrows(MessageLimitException::class.java) { decoder.binary(part(1, 0, 64 * 1024 * 1024 + 1, byteArrayOf(1))) }
        assertFalse(decoder.active)
        assertThrows(IllegalArgumentException::class.java) { decoder.binary(part(1, 0, 0, byteArrayOf(1))) }
    }

    private fun part(id: Int, offset: Int, total: Int, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(16 + bytes.size).putInt(0x43525731).putInt(id).putInt(offset).putInt(total).put(bytes).array()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
