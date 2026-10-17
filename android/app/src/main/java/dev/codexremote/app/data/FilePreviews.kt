package dev.codexremote.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

sealed interface PreviewContent {
    data class Text(val text: String, val truncated: Boolean) : PreviewContent
    data class Image(val bitmap: Bitmap) : PreviewContent
    data class Unsupported(val reason: String) : PreviewContent
}

private const val TEXT_BYTES = 256 * 1024
private const val IMAGE_BYTES = 20 * 1024 * 1024

/** Inspect bytes before decoding; neither the extension nor the HTTP MIME is reliable. */
internal fun readPreview(input: InputStream): PreviewContent {
    val prefix = input.readAtMost(TEXT_BYTES + 1)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(prefix, 0, prefix.size, bounds)
    if (bounds.outMimeType != null) {
        val bytes = prefix + input.readAtMost(IMAGE_BYTES + 1 - prefix.size)
        if (bytes.size > IMAGE_BYTES) return PreviewContent.Unsupported("图片超过 20 MB 预览限制，请下载查看。")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return PreviewContent.Unsupported("图片已损坏或格式不受支持。")
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return bitmap?.let { PreviewContent.Image(it) }
            ?: PreviewContent.Unsupported("无法解码图片，请下载查看。")
    }
    return decodeTextPreview(prefix, TEXT_BYTES)
}

/** REPORT prevents arbitrary binary data from becoming replacement characters. */
internal fun decodeTextPreview(bytes: ByteArray, limit: Int): PreviewContent {
    val truncated = bytes.size > limit
    val input = ByteBuffer.wrap(bytes, 0, minOf(bytes.size, limit))
    val output = CharBuffer.allocate(input.remaining())
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val result = decoder.decode(input, output, !truncated)
    output.flip()
    val text = output.toString()
    if (result.isError || text.any { it < ' ' && it !in "\t\n\r\u000C" }) {
        return PreviewContent.Unsupported("此文件是二进制文件或不支持的文本编码，请下载查看。")
    }
    return PreviewContent.Text(text, truncated)
}

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = limit
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}
