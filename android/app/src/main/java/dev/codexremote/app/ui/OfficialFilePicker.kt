package dev.codexremote.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.uploadAttachments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** The official pick-files contract returns remote paths, after the selected phone files upload. */
@Composable
internal fun rememberOfficialFilePicker(
    api: GatewayApi, serviceId: String, home: String, workspace: String, visible: Boolean,
    onStatus: (String?) -> Unit,
): suspend (JSONObject) -> JSONArray {
    val resolver = LocalContext.current.contentResolver
    var pending by remember { mutableStateOf<CompletableDeferred<List<Uri>>?>(null) }
    var directory by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uris = if (result.resultCode != Activity.RESULT_OK || data == null) emptyList() else {
            data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)
        }
        pending?.complete(uris.distinct())
        pending = null
    }
    DisposableEffect(Unit) {
        onDispose { pending?.cancel(); directory?.cancel() }
    }
    if (visible && directory != null) RemoteDirectoryDialog(api, serviceId, workspace,
        onSelect = { directory?.complete(it); directory = null },
        onDismiss = { directory?.complete(null); directory = null })
    return { params ->
        check(pending == null && directory == null) { "文件选择器已打开" }
        if (params.optString("kind") == "directory") {
            val selection = CompletableDeferred<String?>()
            directory = selection
            try {
                val path = selection.await()
                JSONArray().apply {
                    if (path != null) put(JSONObject().put("label", path.substringAfterLast('/')).put("path", path).put("fsPath", path))
                }
            } finally { if (directory === selection) directory = null }
        } else {
            val selection = CompletableDeferred<List<Uri>>()
            pending = selection
            val multiple = params.optBoolean("allowMultiple", true)
            val extensions = params.optJSONArray("acceptedFileExtensions")
            val types = extensions?.let { values -> (0 until values.length()).map {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(values.getString(it).trimStart('.').lowercase()) ?: "*/*"
            }.distinct() }.orEmpty()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (params.optBoolean("imagesOnly")) "image/*" else types.singleOrNull() ?: "*/*"
                if (!params.optBoolean("imagesOnly") && types.size > 1 && "*/*" !in types) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            }
            try {
                launcher.launch(intent)
                val uris = selection.await().let { if (multiple) it else it.take(1) }
                currentCoroutineContext().ensureActive()
                uploadAttachments(api, serviceId, home, resolver, uris, params, onStatus)
            } finally {
                // A cancelled caller must not allow a late picker result to attach to another request.
                // Keep the slot until the Activity result arrives; disposal cancels it as well.
                if (selection.isCompleted && pending === selection) pending = null
            }
        }
    }
}
