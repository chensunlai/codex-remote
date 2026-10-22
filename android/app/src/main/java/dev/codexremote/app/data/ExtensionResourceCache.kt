package dev.codexremote.app.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Immutable official plugin assets only. No WebView messages, HTML state, or credentials are stored. */
internal class ExtensionResourceCache(
    private val directory: File,
    maxBytes: Long = 1024L * 1024 * 1024,
    private val maxEntries: Int = 16_384,
) {
    private val pending = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()
    private val diskLock = Any()
    @Volatile private var byteLimit = maxBytes.coerceAtLeast(0)
    private val entries = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var indexed = false
    private var storedBytes = 0L
    private val cacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val fetchedBytes = AtomicLong()

    data class Diagnostics(val hits: Long, val misses: Long, val fetchedBytes: Long)

    /** Fetched bytes are decoded asset bytes; the HTTP layer measures actual network traffic. */
    fun diagnostics() = Diagnostics(cacheHits.get(), cacheMisses.get(), fetchedBytes.get())

    /** Changing the limit does not enumerate or delete files on the UI thread. */
    fun setMaxBytes(value: Long): CompletableFuture<Unit> {
        byteLimit = value.coerceAtLeast(0)
        return CompletableFuture.supplyAsync({
            synchronized(diskLock) {
                try { initializeIndex(); trim() } catch (_: Exception) { /* Loading can continue without a writable cache. */ }
            }
        }, maintenance)
    }

    fun load(scope: String, packageSha256: String?, version: String, path: String, fetch: () -> ByteArray): ByteArray {
        // A version string alone cannot identify a package that was replaced on the Agent.
        if (packageSha256 == null || !SHA256.matches(packageSha256)) return fetchAsset(fetch)
        val key = digest("$scope\u0000${packageSha256.lowercase()}\u0000$version\u0000$path".toByteArray()).hex()
        val result = CompletableFuture<ByteArray>()
        val existing = pending.putIfAbsent(key, result)
        if (existing != null) return try { existing.join() } catch (error: CompletionException) { throw error.cause ?: error }
        try {
            val value = read(key)?.also { cacheHits.incrementAndGet() } ?: fetchAsset(fetch).also { write(key, it) }
            result.complete(value)
            return value
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            pending.remove(key, result)
        }
    }

    private fun fetchAsset(fetch: () -> ByteArray): ByteArray {
        cacheMisses.incrementAndGet()
        return fetch().also { fetchedBytes.addAndGet(it.size.toLong()) }
    }

    private fun read(key: String): ByteArray? = synchronized(diskLock) {
        initializeIndex()
        val file = File(directory, key)
        try {
            if (!file.isFile) { removeEntry(key); return null }
            if (file.length() !in HEADER_BYTES.toLong()..MAX_RESOURCE_BYTES + HEADER_BYTES) {
                file.delete(); removeEntry(key)
                return null
            }
            val bytes = DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return@use null
                val expected = ByteArray(32).also(input::readFully)
                val content = input.readBytes()
                content.takeIf { MessageDigest.isEqual(expected, digest(it)) }
            }
            if (bytes == null) { file.delete(); removeEntry(key) }
            else {
                file.setLastModified(System.currentTimeMillis())
                // Updating the entry also advances the in-memory LRU without rescanning the directory.
                rememberEntry(key, file.length())
            }
            bytes
        } catch (_: Exception) {
            file.delete(); removeEntry(key)
            null
        }
    }

    private fun write(key: String, bytes: ByteArray): Unit = synchronized(diskLock) {
        if (bytes.size.toLong() + HEADER_BYTES > byteLimit || bytes.size > MAX_RESOURCE_BYTES) return
        var temporary: File? = null
        try {
            initializeIndex()
            if (!directory.isDirectory && !directory.mkdirs()) return
            temporary = File.createTempFile("asset-", ".tmp", directory)
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.write(digest(bytes))
                output.write(bytes)
            }
            if (!temporary.renameTo(File(directory, key))) return
            rememberEntry(key, bytes.size.toLong() + HEADER_BYTES)
            trim()
        } catch (_: Exception) {
            // Cache eviction, a full disk, or unavailable storage must not prevent the frontend loading.
        } finally {
            temporary?.delete()
        }
    }

    /** The directory is scanned once per cache instance, never after each resource write. */
    private fun initializeIndex() {
        if (indexed) return
        indexed = true
        try {
            directory.listFiles()?.filter { it.isFile }?.sortedBy(File::lastModified)?.forEach { file ->
                if (SHA256.matches(file.name)) rememberEntry(file.name, file.length())
                else file.delete() // Includes interrupted atomic writes from a previous process.
            }
            trim()
        } catch (_: Exception) { /* Storage may be unavailable; uncached loading remains usable. */ }
    }

    private fun rememberEntry(key: String, size: Long) {
        storedBytes += size - (entries.put(key, size) ?: 0L)
    }

    private fun removeEntry(key: String) { storedBytes -= entries.remove(key) ?: 0L }

    private fun trim() {
        val oldest = entries.entries.iterator()
        while ((storedBytes > byteLimit || entries.size > maxEntries) && oldest.hasNext()) {
            val entry = oldest.next()
            val file = File(directory, entry.key)
            if (!file.exists() || file.delete()) {
                storedBytes -= entry.value
                oldest.remove()
            }
        }
    }

    private companion object {
        const val MAGIC = 0x45584331 // EXC1
        const val HEADER_BYTES = 4 + 32
        const val MAX_RESOURCE_BYTES = 12L * 1024 * 1024
        val SHA256 = Regex("[a-fA-F0-9]{64}")
        val maintenance = Executors.newSingleThreadExecutor { operation ->
            Thread(operation, "extension-resource-cache").apply { isDaemon = true }
        }
        fun digest(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
