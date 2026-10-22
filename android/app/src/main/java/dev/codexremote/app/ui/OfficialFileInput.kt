package dev.codexremote.app.ui

import android.net.Uri
import android.app.Activity
import android.content.Intent
import android.webkit.ValueCallback

/** One browser file request owns one Android picker result, including cancellation after navigation. */
internal class OfficialFileInput {
    private var inFlight = false
    private var callback: ValueCallback<Array<Uri>>? = null
    private var multiple = false

    fun begin(result: ValueCallback<Array<Uri>>, allowMultiple: Boolean): Boolean {
        if (inFlight) { result.onReceiveValue(null); return false }
        inFlight = true
        callback = result
        multiple = allowMultiple
        return true
    }

    fun complete(uris: Array<Uri>?) {
        val receiver = callback
        callback = null
        inFlight = false
        val accepted = uris?.filter { it.scheme == "content" }?.distinct().orEmpty()
        receiver?.onReceiveValue(accepted.take(if (multiple) accepted.size else 1).toTypedArray().takeIf { it.isNotEmpty() })
    }

    fun cancel() {
        val receiver = callback
        callback = null
        receiver?.onReceiveValue(null)
        // A late result still belongs to the old request. Do not hand it to a
        // replacement document while the Android activity is in flight.
    }

    companion object {
        fun selectedUris(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val clipped = data.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
            return clipped?.takeIf { it.isNotEmpty() }?.toTypedArray() ?: data.data?.let { arrayOf(it) }
        }
    }
}
