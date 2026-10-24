package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.codexremote.app.BuildConfig
import dev.codexremote.app.data.ProductRelease
import dev.codexremote.app.data.ProductUpdates
import dev.codexremote.app.data.productUpdateAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

@Composable
internal fun ProductUpdatePanel() {
    val releases = remember { ProductUpdates() }
    DisposableEffect(releases) { onDispose { releases.close() } }
    var release by remember { mutableStateOf<ProductRelease?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current

    fun open(url: String) {
        runCatching { browser.openUri(url) }.onFailure { error = "无法打开浏览器：${it.message}" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("App ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        release?.let { latest ->
            Text("最新稳定版：${latest.version}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val appUpdate = productUpdateAvailable(BuildConfig.VERSION_NAME, latest.version)
            Text(if (appUpdate == true) "App 有可用更新" else "App 已是当前或更新版本")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (appUpdate == true && latest.apkUrl != null) {
                    Button(onClick = { open(latest.apkUrl) }, shape = MaterialTheme.shapes.small) { Text("下载 App") }
                }
                OutlinedButton(onClick = { open(latest.pageUrl) }, shape = MaterialTheme.shapes.small) { Text("发布说明") }
            }
            Text("下载完成后安装 APK 即可更新 App。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = {
            if (!busy) scope.launch {
                busy = true
                error = null
                try {
                    val latest = releases.latest()
                    coroutineContext.ensureActive()
                    release = latest
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "检查更新失败" }
                finally { busy = false }
            }
        }, enabled = !busy, shape = MaterialTheme.shapes.small) {
            Text(if (busy) "正在检查…" else "检查 App 更新")
        }
    }
}
