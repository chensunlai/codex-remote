package dev.codexremote.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.codexremote.app.data.GatewayApi
import dev.codexremote.app.data.ProductRelease
import dev.codexremote.app.data.ProductUpdates
import dev.codexremote.app.data.objects
import dev.codexremote.app.data.productUpdateAvailable
import dev.codexremote.app.model.GatewayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A panel owns an immutable credential snapshot and cancels it when removed. */
@Composable
internal fun GatewayAdminPanel(config: GatewayConfig, currentTokenId: String?, onSettingsSaved: () -> Unit) {
    val api = remember(config) { GatewayApi().apply { configure(config) } }
    val updates = remember { ProductUpdates() }
    DisposableEffect(api, updates) { onDispose { api.clear(); updates.close() } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf("访问令牌") }
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var tokens by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var services by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var runtime by remember { mutableStateOf<JSONObject?>(null) }
    var upload by remember { mutableStateOf("") }
    var download by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("user") }
    var create by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf<JSONObject?>(null) }
    var copied by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    var removeService by remember { mutableStateOf<JSONObject?>(null) }
    var operation by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<ProductRelease?>(null) }
    var poll by remember { mutableStateOf(0) }

    suspend fun refresh() {
        val nextOverview = api.adminOverview()
        val nextTokens = api.adminTokens().objects()
        val nextServices = api.adminServices().objects()
        val settings = api.adminSettings()
        val nextRuntime = api.gatewayRuntime()
        coroutineContext.ensureActive()
        overview = nextOverview
        tokens = nextTokens
        services = nextServices
        upload = bytesToMiBInput(settings.getLong("maxUploadBytes"))
        download = bytesToMiBInput(settings.getLong("maxDownloadBytes"))
        runtime = nextRuntime
    }

    fun act(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }

    LaunchedEffect(api) {
        busy = true
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法加载管理员设置" }
        finally { busy = false }
    }
    val pendingOperationId = runtime?.optJSONObject("operation")?.takeIf {
        it.optString("state") in listOf("queued", "running")
    }?.optString("id")
    LaunchedEffect(api, poll, pendingOperationId) {
        if (poll == 0 && pendingOperationId == null) return@LaunchedEffect
        // A successful restart closes the current HTTP connection. Polling is read-only.
        val finished = withTimeoutOrNull(60_000) {
            while (true) {
                delay(1_500)
                try {
                    val next = api.gatewayRuntime()
                    coroutineContext.ensureActive()
                    runtime = next
                    if (next.optJSONObject("operation")?.optString("state") !in listOf("queued", "running")) {
                        notice = "Gateway 已重新连接，请查看操作结果。"
                        return@withTimeoutOrNull true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Service may be restarting. */ }
            }
        }
        if (finished != true) error = "暂时无法确认 Gateway 操作结果，请稍后刷新。"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        overview?.let {
            Text("Gateway ${it.optString("version")}", style = MaterialTheme.typography.titleMedium)
            Text("${services.count { service -> service.optBoolean("online") }} / ${services.size} 台在线 · ${tokens.size} 个令牌",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${it.optString("host")}:${it.optInt("port")}\n${it.optString("dataDirectory")}", style = MonoTextStyle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("访问令牌", "参数", "服务").forEach { item ->
                if (tab == item) Button(onClick = { tab = item }, shape = MaterialTheme.shapes.small) { Text(item) }
                else OutlinedButton(onClick = { tab = item }, shape = MaterialTheme.shapes.small) { Text(item) }
            }
        }
        when (tab) {
            "访问令牌" -> {
                Text("普通令牌隔离各自的服务；管理员令牌可以管理整个 Gateway。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tokens.forEach { token ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(token.optString("label").ifBlank { "未命名" })
                            Text((if (token.optString("role") == "admin") "管理员" else "普通用户") +
                                if (token.optString("id") == currentTokenId) " · 当前令牌" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { revoke = token }, enabled = !busy && token.optString("id") != currentTokenId) { Text("撤销") }
                    }
                }
                OutlinedButton(onClick = { label = ""; role = "user"; create = true }, enabled = !busy,
                    shape = MaterialTheme.shapes.small) { Text("创建令牌") }
            }
            "参数" -> {
                Text("文件上传、下载及预览的单文件上限。保存后立即生效。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = upload, onValueChange = { upload = it }, label = { Text("上传上限（MiB）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = download, onValueChange = { download = it }, label = { Text("下载 / 预览上限（MiB）") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = { act {
                    api.saveAdminSettings(miBInputToBytes(upload), miBInputToBytes(download))
                    coroutineContext.ensureActive()
                    onSettingsSaved()
                    notice = "文件限制已保存并生效"
                } }, enabled = !busy && upload.isNotBlank() && download.isNotBlank(), shape = MaterialTheme.shapes.small) { Text("保存参数") }
            }
            "服务" -> {
                Text("所有令牌所属的 Agent。在线服务需先断开才能删除。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (services.isEmpty()) Text("尚无已注册服务")
                services.forEach { service ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${service.optString("name")} · ${if (service.optBoolean("online")) "在线" else "离线"}",
                            color = if (service.optBoolean("online")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        val version = service.optString("agentVersion").takeUnless { it.isBlank() || it == "null" }
                        Text("${service.optString("hostname")} · ${service.optString("platform")} ${service.optString("arch")}\nAgent ${version ?: "版本未知"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("${service.optString("home")}\n服务 ID：${service.optString("id")}\n所属令牌：${service.optString("ownerId")}", style = MonoTextStyle)
                        Text("最近连接：${formatConnectionTime(service)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        latest?.let { release ->
                            Text(when (productUpdateAvailable(version, release.version)) {
                                true -> "可更新至 ${release.version}"
                                false -> "已是当前或更新版本"
                                null -> "无法比较版本"
                            }, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { removeService = service }, enabled = !busy && !service.optBoolean("online")) { Text("删除离线服务") }
                        HorizontalDivider()
                    }
                }
                OutlinedButton(onClick = { act {
                    val release = updates.latest()
                    val current = api.adminServices().objects()
                    coroutineContext.ensureActive()
                    latest = release
                    services = current
                } }, enabled = !busy, shape = MaterialTheme.shapes.small) { Text("检查 Agent 更新") }
                latest?.let { Text("最新稳定版 ${it.version}；请在 Agent 所在服务器更新安装。", style = MaterialTheme.typography.bodySmall) }
            }
        }
        HorizontalDivider()
        Text("Gateway 更新与重启", style = MaterialTheme.typography.titleMedium)
        runtime?.let { status ->
            Text("运行版本：${status.optString("currentVersion").ifBlank { "未知" }}\n最新稳定版：${status.optString("latestVersion").takeUnless { it.isBlank() || it == "null" } ?: "尚未检查"}")
            status.optJSONObject("operation")?.let { op ->
                val state = when (op.optString("state")) { "queued" -> "等待执行"; "running" -> "进行中"; "succeeded" -> "完成"; else -> "失败" }
                Text("${if (op.optString("type") == "update") "更新" else "重启"}$state")
                op.optString("output").takeUnless { it.isBlank() || it == "null" }?.let { Text(it.takeLast(4000), style = MonoTextStyle) }
                op.optString("error").takeUnless { it.isBlank() || it == "null" }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            status.optString("checkError").takeUnless { it.isBlank() || it == "null" }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            status.optString("unavailableReason").takeUnless { it.isBlank() || it == "null" }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val running = runtime?.optJSONObject("operation")?.optString("state") in listOf("queued", "running")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act { runtime = api.gatewayRuntime(true) } }, enabled = !busy && !running,
                shape = MaterialTheme.shapes.small) { Text("检查更新") }
            Button(onClick = { operation = "update" }, enabled = !busy && !running && runtime?.optBoolean("updateSupported") == true &&
                runtime?.optBoolean("updateAvailable") == true, shape = MaterialTheme.shapes.small) { Text("更新") }
            OutlinedButton(onClick = { operation = "restart" }, enabled = !busy && !running && runtime?.optBoolean("restartSupported") == true,
                shape = MaterialTheme.shapes.small) { Text("重启") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        TextButton(onClick = { act { refresh() } }, enabled = !busy) { Text(if (busy) "处理中…" else "刷新管理信息") }
    }

    if (create) CodexDialog(onDismissRequest = { if (!busy) create = false }, title = { Text("创建访问令牌") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("名称") }, singleLine = true, enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("user" to "普通用户", "admin" to "管理员").forEach { (value, title) ->
                        OutlinedButton(onClick = { role = value }, enabled = !busy, shape = MaterialTheme.shapes.small) {
                            Text(if (role == value) "✓ $title" else title)
                        }
                    }
                }
                if (role == "admin") Text("管理员令牌可创建令牌、修改参数、更新和重启 Gateway。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { act {
            val result = api.createAdminToken(label.trim(), role)
            coroutineContext.ensureActive()
            secret = result
            copied = false
            create = false
            tokens = api.adminTokens().objects()
        } }, enabled = !busy && label.isNotBlank()) { Text("创建") } },
        dismissButton = { TextButton(onClick = { create = false }, enabled = !busy) { Text("取消") } })

    secret?.let { created ->
        CodexDialog(onDismissRequest = { secret = null }, title = { Text("令牌已创建") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("完整令牌仅显示这一次，请复制后保存。")
                androidx.compose.foundation.text.selection.SelectionContainer { Text(created.getString("token"), style = MonoTextStyle) }
            }
        }, confirmButton = { TextButton(onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Gateway 访问令牌", created.getString("token"))
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setPrimaryClip(clip)
            copied = true
        }) { Text(if (copied) "已复制" else "复制令牌") } }, dismissButton = { TextButton(onClick = { secret = null }) { Text("关闭") } })
    }
    revoke?.let { token ->
        CodexDialog(onDismissRequest = { revoke = null }, title = { Text("撤销令牌？") },
            text = { Text("“${token.optString("label") }”将立即失效，使用它的 App 和 Agent 会断开连接。") },
            confirmButton = { TextButton(onClick = { revoke = null; act { api.revokeAdminToken(token.getString("id")); refresh() } }) { Text("撤销") } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("取消") } })
    }
    removeService?.let { service ->
        CodexDialog(onDismissRequest = { removeService = null }, title = { Text("删除离线服务？") },
            text = { Text("删除“${service.optString("name")}”的注册记录。不会删除远端文件；Agent 再次连接时会重新注册。") },
            confirmButton = { TextButton(onClick = { removeService = null; act {
                api.deleteAdminService(service.getString("ownerId"), service.getString("id")); refresh()
            } }) { Text("删除") } }, dismissButton = { TextButton(onClick = { removeService = null }) { Text("取消") } })
    }
    operation?.let { action ->
        CodexDialog(onDismissRequest = { operation = null }, title = { Text(if (action == "update") "更新 Gateway？" else "重启 Gateway？") },
            text = { Text("所有 App 和 Agent 与 Gateway 的连接将暂时断开，并在服务恢复后重连。远端任务由 Agent 继续执行。") },
            confirmButton = { TextButton(onClick = {
                operation = null
                act {
                    try { runtime = api.gatewayOperation(action == "update") }
                    finally { poll += 1 }
                    notice = "已提交操作，正在等待 Gateway 恢复"
                }
            }) { Text("确认${if (action == "update") "更新" else "重启"}") } },
            dismissButton = { TextButton(onClick = { operation = null }) { Text("取消") } })
    }
}

internal fun miBInputToBytes(value: String): Long {
    val amount = value.trim().toBigDecimalOrNull() ?: error("请输入有效的 MiB 数值")
    val bytes = try { amount.multiply(1048576.toBigDecimal()).longValueExact() }
    catch (_: ArithmeticException) { error("MiB 数值必须对应整数个字节") }
    require(bytes in 1..9_007_199_254_740_991L) { "文件限制必须大于 0 且不超过 8589934591 MiB" }
    return bytes
}

private fun bytesToMiBInput(bytes: Long): String = bytes.toBigDecimal().divide(1048576.toBigDecimal()).stripTrailingZeros().toPlainString()

private fun formatConnectionTime(service: JSONObject): String {
    if (!service.has("lastConnectedAt") || service.isNull("lastConnectedAt")) return "未知"
    return runCatching {
        val raw = service.get("lastConnectedAt")
        val instant = if (raw is Number) Instant.ofEpochMilli(raw.toLong()) else Instant.parse(raw.toString())
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(instant)
    }.getOrDefault("未知")
}
