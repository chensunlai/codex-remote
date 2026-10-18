package dev.codexremote.app.ui

import org.junit.Assert.*
import org.junit.Test

class GatewaySettingsTest {
    @Test fun convertsFileLimitsWithoutRounding() {
        assertEquals(10L * 1024 * 1024, miBInputToBytes("10"))
        assertEquals(1572864L, miBInputToBytes("1.5"))
        assertEquals(1L, miBInputToBytes("0.00000095367431640625"))
    }
    @Test fun rejectsInvalidOrUnsafeLimits() {
        listOf("0", "-1", "", "NaN", "0.00000001", "8589934592", "1e100").forEach {
            assertTrue("Must reject $it", runCatching { miBInputToBytes(it) }.isFailure)
        }
    }
}
