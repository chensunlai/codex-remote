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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun GatewaySetupScreen(
    initial: GatewayConfig?,
    loading: Boolean,
    error: String?,
    onSave: (String, String) -> Unit,
    onClearError: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        GatewayForm(
            initial = initial,
            loading = loading,
            error = error,
            showBrand = true,
            submitLabel = "连接网关",
            onSave = onSave,
            onChange = onClearError,
        )
    }
}

@Composable
fun GatewayForm(
    initial: GatewayConfig?,
    loading: Boolean,
    error: String?,
    showBrand: Boolean,
    submitLabel: String,
    onSave: (String, String) -> Unit,
    onChange: () -> Unit = {},
) {
    var address by remember(initial) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var token by remember(initial) { mutableStateOf(initial?.token.orEmpty()) }
    var revealToken by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showBrand) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
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
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; onChange() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("网关地址") },
            placeholder = { Text("https://gateway.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; onChange() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("访问令牌") },
            singleLine = true,
            visualTransformation = if (revealToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { revealToken = !revealToken }) {
                    Icon(
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
            onClick = { onSave(address, token) },
            enabled = !loading && address.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(submitLabel)
            }
        }
    }
}
