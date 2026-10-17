package dev.codexremote.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePreviewsTest {
    @Test fun binaryAndMalformedUtf8NeverBecomeText() {
        for (bytes in listOf(byteArrayOf(0, 1, 2), byteArrayOf(0x89.toByte(), 80, 78, 71), byteArrayOf(0xC3.toByte()))) {
            assertTrue(decodeTextPreview(bytes, 256) is PreviewContent.Unsupported)
        }
    }

    @Test fun utf8TextPreservesChineseAndNewlines() {
        val text = "图片预览\n\t正常 😀"
        assertEquals(PreviewContent.Text(text, false), decodeTextPreview(text.toByteArray(), 256))
    }

    @Test fun truncatedUtf8DoesNotIntroduceReplacementCharacters() {
        assertEquals(PreviewContent.Text("abc", true), decodeTextPreview("abc图片".toByteArray(), 5))
    }

    @Test fun emptyFileRemainsAnEmptyTextPreview() {
        assertEquals(PreviewContent.Text("", false), decodeTextPreview(byteArrayOf(), 256))
    }
}
