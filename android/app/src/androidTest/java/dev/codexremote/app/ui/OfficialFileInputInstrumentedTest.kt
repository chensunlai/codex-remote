package dev.codexremote.app.ui

import android.net.Uri
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.webkit.ValueCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfficialFileInputInstrumentedTest {
    @Test fun documentPickerMultipleSelectionUsesClipDataEvenWhenIntentDataIsEmpty() {
        val image = Uri.parse("content://fixture/image.png")
        val text = Uri.parse("content://fixture/document.txt")
        val result = Intent().apply { clipData = ClipData.newRawUri("files", image).apply { addItem(ClipData.Item(text)) } }
        assertEquals(listOf(image, text), OfficialFileInput.selectedUris(Activity.RESULT_OK, result)?.toList())
        assertNull(OfficialFileInput.selectedUris(Activity.RESULT_CANCELED, result))
        assertEquals(listOf(image), OfficialFileInput.selectedUris(Activity.RESULT_OK, Intent().setData(image))?.toList())
    }

    @Test fun selectedContentUrisReachOnlyTheOwningBrowserRequest() {
        val picker = OfficialFileInput()
        val replies = mutableListOf<Array<Uri>?>()
        assertTrue(picker.begin(ValueCallback { replies.add(it) }, allowMultiple = true))
        val image = Uri.parse("content://fixture/picture.png")
        val text = Uri.parse("content://fixture/中文.txt")
        picker.complete(arrayOf(image, text, image, Uri.parse("file:///private/file")))
        assertEquals(listOf(image, text), replies.single()?.toList())
        picker.complete(arrayOf(text))
        assertEquals(1, replies.size)
    }

    @Test fun navigationCancelsTheOldCallbackAndNeverRoutesItsLateFilesToTheNewDocument() {
        val picker = OfficialFileInput()
        val original = mutableListOf<Array<Uri>?>()
        val replacement = mutableListOf<Array<Uri>?>()
        assertTrue(picker.begin(ValueCallback { original.add(it) }, allowMultiple = false))
        picker.cancel()
        assertNull(original.single())
        assertFalse(picker.begin(ValueCallback { replacement.add(it) }, allowMultiple = false))
        assertNull(replacement.single())
        picker.complete(arrayOf(Uri.parse("content://fixture/late.png")))
        assertEquals(1, original.size)
        assertEquals(1, replacement.size)
        assertTrue(picker.begin(ValueCallback { replacement.add(it) }, allowMultiple = false))
        picker.complete(null)
        assertEquals(2, replacement.size)
        assertNull(replacement.last())
    }

    @Test fun singleSelectionAndPickerCancellationMatchTheHtmlInputContract() {
        val picker = OfficialFileInput()
        val replies = mutableListOf<Array<Uri>?>()
        val first = Uri.parse("content://fixture/one.txt")
        assertTrue(picker.begin(ValueCallback { replies.add(it) }, allowMultiple = false))
        picker.complete(arrayOf(first, Uri.parse("content://fixture/two.txt")))
        assertEquals(listOf(first), replies.single()?.toList())
        assertTrue(picker.begin(ValueCallback { replies.add(it) }, allowMultiple = false))
        picker.complete(null)
        assertNull(replies.last())
    }
}
