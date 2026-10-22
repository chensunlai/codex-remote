package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.codexremote.app.model.GatewayConfig
import dev.codexremote.app.data.UiPreferences

@Composable
fun GatewaySetupScreen(
    initial: GatewayConfig?,
    loading: Boolean,
    error: String?,
    onSave: (String, String, String) -> Unit,
    onClearError: () -> Unit,
    allowHttp: Boolean,
    multipleEndpoints: Boolean,
    onExperiments: (Boolean, Boolean) -> Unit,
    customWindowLimit: Boolean = false,
    maxChatPages: Int = UiPreferences.DEFAULT_MAX_CHAT_PAGES,
    customCacheLimit: Boolean = false,
    resourceCacheBytes: Long = UiPreferences.DEFAULT_RESOURCE_CACHE_BYTES,
    onCustomWindowLimitChange: (Boolean) -> Unit = {},
    onMaxChatPagesChange: (Int) -> Unit = {},
    onCustomCacheLimitChange: (Boolean) -> Unit = {},
    onResourceCacheBytesChange: (Long) -> Unit = {},
    windowMode: Boolean = true,
    onWindowModeChange: (Boolean) -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            GatewayForm(
                initial = initial,
                loading = loading,
                error = error,
                showBrand = true,
                submitLabel = "连接网关",
                onSave = onSave,
                onChange = onClearError,
                multipleEndpoints = multipleEndpoints,
            )
            ExperimentalSettings(allowHttp, multipleEndpoints, !loading, onExperiments,
                customWindowLimit, maxChatPages, customCacheLimit, resourceCacheBytes,
                onCustomWindowLimitChange, onMaxChatPagesChange, onCustomCacheLimitChange, onResourceCacheBytesChange,
                windowMode = windowMode, onWindowModeChange = onWindowModeChange)
        }
    }
}

@Composable
fun GatewayForm(
    initial: GatewayConfig?,
    loading: Boolean,
    error: String?,
    showBrand: Boolean,
    submitLabel: String,
    onSave: (String, String, String) -> Unit,
    onChange: () -> Unit = {},
    multipleEndpoints: Boolean = false,
) {
    var address by remember(initial?.baseUrl) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var endpoints by remember(initial?.baseUrl, initial?.endpoints) { mutableStateOf(initial?.endpoints.orEmpty().joinToString("\n")) }
    var token by remember(initial?.baseUrl, initial?.token) { mutableStateOf(initial?.token.orEmpty()) }
    var revealToken by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showBrand) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    CodexIcons.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Codex Remote", style = MaterialTheme.typography.headlineSmall)
                    Text("网关连接", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        CodexTextField(
            value = address,
            onValueChange = { address = it; onChange() },
            modifier = Modifier.fillMaxWidth(),
            label = "网关地址",
            placeholder = "https://gateway.example.com",
            enabled = !loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        if (multipleEndpoints) {
            CodexTextField(value = endpoints, onValueChange = { endpoints = it; onChange() },
                label = "优先入口（每行一个，按顺序）", modifier = Modifier.fillMaxWidth(),
                placeholder = "http://192.168.1.10:6767\nhttps://gateway.example.com",
                enabled = !loading, singleLine = false)
            Text("只填写同一 Gateway 的入口，主地址作为最后兜底。内网入口可放第一行；HTTP 需单独开启。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CodexTextField(
            value = token,
            onValueChange = { token = it; onChange() },
            modifier = Modifier.fillMaxWidth(),
            label = "访问令牌",
            enabled = !loading,
            visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { revealToken = !revealToken }, enabled = !loading) {
                    AppIcon(
                        if (revealToken) CodexIcons.VisibilityOff else CodexIcons.Visibility,
                        contentDescription = if (revealToken) "隐藏令牌" else "显示令牌",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = { onSave(address, token, endpoints) },
            enabled = !loading && address.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.height(48.dp).align(Alignment.End),
            shape = MaterialTheme.shapes.small,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Text("正在连接", Modifier.padding(start = 8.dp))
            } else {
                Text(submitLabel)
            }
        }
    }
}
