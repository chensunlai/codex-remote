package dev.codexremote.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ExtensionResourceCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val packageId = "a".repeat(64)

    @Test fun assetsSurviveCacheRecreationWithoutAnotherDownload() {
        val directory = temporary.newFolder()
        val bytes = "official unchanged resource 图片".toByteArray()
        assertArrayEquals(bytes, ExtensionResourceCache(directory).load("owner-service", packageId, "1.0", "webview/index.js") { bytes })
        assertArrayEquals(bytes, ExtensionResourceCache(directory).load("owner-service", packageId, "1.0", "webview/index.js") {
            error("A persistent cache hit must not download")
        })
    }

    @Test fun packageServiceCredentialVersionAndPathIdentitiesRemainIsolated() {
        val cache = ExtensionResourceCache(temporary.newFolder())
        val calls = AtomicInteger()
        fun load(scope: String = "owner-service", id: String = packageId, version: String = "1.0", path: String = "index.js") =
            cache.load(scope, id, version, path) { byteArrayOf(calls.incrementAndGet().toByte()) }
        assertArrayEquals(byteArrayOf(1), load())
        assertArrayEquals(byteArrayOf(2), load(scope = "different-owner-service"))
        assertArrayEquals(byteArrayOf(3), load(id = "b".repeat(64)))
        assertArrayEquals(byteArrayOf(4), load(version = "2.0"))
        assertArrayEquals(byteArrayOf(5), load(path = "other.js"))
        assertArrayEquals(byteArrayOf(1), load())
        assertEquals(5, calls.get())
    }

    @Test fun simultaneousRequestsForAnAssetUseOneDownload() {
        val cache = ExtensionResourceCache(temporary.newFolder())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map {
                executor.submit<ByteArray> {
                    cache.load("scope", packageId, "1.0", "index.js") {
                        calls.incrementAndGet()
                        started.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        "resource".toByteArray()
                    }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
            tasks.forEach { assertArrayEquals("resource".toByteArray(), it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun missingOrInvalidPackageIdentityNeverCachesAnUnidentifiedPackage() {
        val directory = temporary.newFolder()
        val cache = ExtensionResourceCache(directory)
        var calls = 0
        for (id in listOf(null, "1.0")) repeat(2) {
            cache.load("scope", id, "1.0", "index.js") { calls += 1; byteArrayOf(1) }
        }
        assertEquals(4, calls)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedDownloadsNeverPoisonTheCache() {
        val cache = ExtensionResourceCache(temporary.newFolder())
        runCatching { cache.load("scope", packageId, "1.0", "index.js") { error("HTTP 401") } }
            .onSuccess { error("The original download error must be delivered") }
        assertArrayEquals(byteArrayOf(2), cache.load("scope", packageId, "1.0", "index.js") { byteArrayOf(2) })
    }

    @Test fun corruptStoredBytesAreRefetchedInsteadOfServingBrokenJavascript() {
        val directory = temporary.newFolder()
        val cache = ExtensionResourceCache(directory)
        cache.load("scope", packageId, "1.0", "index.js") { "official".toByteArray() }
        val entry = directory.listFiles()!!.single()
        val changed = entry.readBytes().also { it[it.lastIndex] = 0 }
        entry.writeBytes(changed)
        assertArrayEquals("repaired".toByteArray(), cache.load("scope", packageId, "1.0", "index.js") { "repaired".toByteArray() })
    }

    @Test fun cacheHasBothByteAndEntryLimitsAndKeepsRecentlyUsedAssets() {
        val directory = temporary.newFolder()
        val cache = ExtensionResourceCache(directory, maxBytes = 80, maxEntries = 1)
        cache.load("scope", packageId, "1.0", "a.js") { ByteArray(20) }
        cache.load("scope", packageId, "1.0", "b.js") { ByteArray(20) { 1 } }
        assertTrue(directory.listFiles()!!.sumOf(File::length) <= 80)
        assertEquals(1, directory.listFiles()!!.size)
        assertArrayEquals(ByteArray(20) { 1 }, cache.load("scope", packageId, "1.0", "b.js") { error("Latest asset should remain") })
        cache.load("scope", packageId, "1.0", "too-large.js") { ByteArray(100) }
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun unavailableCacheStorageDoesNotPreventLoading() {
        val file = temporary.newFile()
        val cache = ExtensionResourceCache(file)
        assertArrayEquals(byteArrayOf(1), cache.load("scope", packageId, "1.0", "index.js") { byteArrayOf(1) })
    }
}
