package dev.codexremote.app.data

import org.junit.Assert.*
import org.junit.Test

class ProductUpdatesTest {
    @Test fun comparesVersionNumbersWithoutLexicalOrdering() {
        assertEquals(true, productUpdateAvailable("2.9.0", "2.10.0"))
        assertEquals(false, productUpdateAvailable("3.0.0", "2.10.0"))
        assertEquals(false, productUpdateAvailable("2.1.0", "2.1.0"))
    }
    @Test fun debugBuildDoesNotProduceFalseUpdateAndPrereleaseDoes() {
        assertEquals(false, productUpdateAvailable("2.1.0-debug", "2.1.0"))
        assertEquals(true, productUpdateAvailable("2.1.0-rc.1", "2.1.0"))
        assertEquals(false, productUpdateAvailable("v2.1.0+build.4", "2.1.0"))
    }
    @Test fun unknownVersionDoesNotClaimAnUpdate() {
        assertNull(productUpdateAvailable(null, "2.1.0"))
        assertNull(productUpdateAvailable("unknown", "2.1.0"))
        assertNull(productUpdateAvailable("2.1.0", "2.2.0-beta"))
        assertNull(productUpdateAvailable("99999999999999999999.0.0", "2.1.0"))
    }
    @Test fun opensOnlyExactOfficialReleaseUrls() {
        val suffix = "/releases/tag/v2.1.0"
        assertTrue(officialReleaseUrl("https://github.com/chensunlai/codex-remote$suffix", suffix))
        listOf("https://github.com.evil.invalid/chensunlai/codex-remote$suffix", "https://user@github.com/chensunlai/codex-remote$suffix",
            "http://github.com/chensunlai/codex-remote$suffix", "https://github.com/chensunlai/other$suffix",
            "https://github.com/chensunlai/codex-remote$suffix?redirect=evil").forEach { assertFalse(officialReleaseUrl(it, suffix)) }
    }
}
