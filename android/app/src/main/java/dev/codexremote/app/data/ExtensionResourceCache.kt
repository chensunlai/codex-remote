package dev.codexremote.app.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/** Immutable official plugin assets only. No WebView messages, HTML state, or credentials are stored. */
internal class ExtensionResourceCache(
    private val directory: File,
    private val maxBytes: Long = 96L * 1024 * 1024,
    private val maxEntries: Int = 1024,
) {
    private val pending = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()
    private val diskLock = Any()

    fun load(scope: String, packageSha256: String?, version: String, path: String, fetch: () -> ByteArray): ByteArray {
        // A version string alone cannot identify a package that was replaced on the Agent.
        if (packageSha256 == null || !SHA256.matches(packageSha256)) return fetch()
        val key = digest("$scope\u0000${packageSha256.lowercase()}\u0000$version\u0000$path".toByteArray()).hex()
        val result = CompletableFuture<ByteArray>()
        val existing = pending.putIfAbsent(key, result)
        if (existing != null) return try { existing.join() } catch (error: CompletionException) { throw error.cause ?: error }
        try {
            val value = read(key) ?: fetch().also { write(key, it) }
            result.complete(value)
            return value
        } catch (error: Throwable) {
            result.completeExceptionally(error)
            throw error
        } finally {
            pending.remove(key, result)
        }
    }

    private fun read(key: String): ByteArray? = synchronized(diskLock) {
        val file = File(directory, key)
        try {
            if (!file.isFile) return null
            if (file.length() !in HEADER_BYTES.toLong()..minOf(maxBytes, MAX_RESOURCE_BYTES) + HEADER_BYTES) {
                file.delete()
                return null
            }
            val bytes = DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return@use null
                val expected = ByteArray(32).also(input::readFully)
                val content = input.readBytes()
                content.takeIf { MessageDigest.isEqual(expected, digest(it)) }
            }
            if (bytes == null) file.delete() else file.setLastModified(System.currentTimeMillis())
            bytes
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun write(key: String, bytes: ByteArray): Unit = synchronized(diskLock) {
        if (bytes.size.toLong() + HEADER_BYTES > maxBytes || bytes.size > MAX_RESOURCE_BYTES) return
        var temporary: File? = null
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            temporary = File.createTempFile("asset-", ".tmp", directory)
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.write(digest(bytes))
                output.write(bytes)
            }
            if (!temporary.renameTo(File(directory, key))) return
            val entries = directory.listFiles()?.filter { it.isFile }
                ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { if (it.name == key) 1 else 0 }).orEmpty()
            var total = entries.sumOf(File::length)
            var count = entries.size
            for (entry in entries) {
                if (total <= maxBytes && count <= maxEntries) break
                val length = entry.length()
                if (entry.delete()) { total -= length; count -= 1 }
            }
        } catch (_: Exception) {
            // Cache eviction, a full disk, or unavailable storage must not prevent the frontend loading.
        } finally {
            temporary?.delete()
        }
    }

    private companion object {
        const val MAGIC = 0x45584331 // EXC1
        const val HEADER_BYTES = 4 + 32
        const val MAX_RESOURCE_BYTES = 12L * 1024 * 1024
        val SHA256 = Regex("[a-fA-F0-9]{64}")
        fun digest(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
