package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.app.data.ExtensionSurface
import org.json.JSONArray
import org.json.JSONObject

/** Native presentation of public editor-host operations; no plugin messages are interpreted. */
@Composable
internal fun OfficialDiffPane(surface: ExtensionSurface, onAction: (String) -> Unit) {
    val diff = surface.diff ?: return
    var proposed by remember(surface.id) { mutableStateOf(true) }
    val originals = remember(diff.originalText) { diff.originalText.lines() }
    val modified = remember(diff.modifiedText) { diff.modifiedText.lines() }
    val commonPrefix = remember(diff) {
        var count = 0
        while (count < minOf(originals.size, modified.size) && originals[count] == modified[count]) count++
        count
    }
    val commonSuffix = remember(diff) {
        var count = 0
        while (count < minOf(originals.size, modified.size) - commonPrefix &&
            originals[originals.lastIndex - count] == modified[modified.lastIndex - count]) count++
        count
    }
    val lines = if (proposed) modified else originals
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = colors.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(surface.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (proposed) diff.modifiedUri else diff.originalUri,
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { proposed = false }, enabled = proposed) { Text("原内容") }
                    TextButton(onClick = { proposed = true }, enabled = !proposed) { Text("提议内容") }
                    Text("只读预览", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                }
            }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                itemsIndexed(lines) { index, line ->
                    val changed = index >= commonPrefix && index < lines.size - commonSuffix
                    Surface(color = if (!changed) colors.surface else if (proposed) colors.primaryContainer else colors.errorContainer) {
                        Text("${index + 1}  $line", Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            style = MonoTextStyle, softWrap = false)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            if ("reject" in surface.actions) TextButton(onClick = { onAction("reject") }, enabled = !surface.pending) { Text("拒绝全部") }
            if ("accept" in surface.actions) Button(onClick = { onAction("accept") }, enabled = !surface.pending) { Text("接受全部") }
        }
    }
}

@Composable
internal fun OfficialInputDialog(params: JSONObject, onResult: (String?) -> Unit) {
    var value by remember(params) { mutableStateOf(params.optString("value")) }
    CodexDialog(onDismissRequest = { onResult(null) },
        title = { Text(params.optString("title").ifBlank { "输入" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexTextField(value, { value = it }, label = params.optString("prompt").ifBlank { "内容" },
                    placeholder = params.optString("placeHolder"),
                    visualTransformation = if (params.optBoolean("password")) PasswordVisualTransformation() else VisualTransformation.None)
                params.optString("validationMessage").takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = { TextButton(onClick = { onResult(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("取消") } })
}

@Composable
internal fun OfficialQuickPickDialog(params: JSONObject, onResult: (Any?) -> Unit) {
    val items = params.optJSONArray("items") ?: JSONArray()
    val multiple = params.optBoolean("canPickMany")
    var filter by remember(params) { mutableStateOf("") }
    var selected by remember(params) { mutableStateOf((0 until items.length()).filter { items.optJSONObject(it)?.optBoolean("picked") == true }.toSet()) }
    CodexDialog(onDismissRequest = { onResult(null) },
        title = { Text(params.optString("title").ifBlank { "选择" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CodexTextField(filter, { filter = it }, label = params.optString("placeHolder").ifBlank { "搜索选项" })
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(items.length()) { index ->
                        val item = items.optJSONObject(index)
                        val label = item?.optString("label") ?: items.optString(index)
                        val description = item?.optString("description").orEmpty()
                        val detail = item?.optString("detail").orEmpty()
                        if (item?.optInt("kind") == -1) {
                            if (label.isNotBlank()) Text(label, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if ("$label $description $detail".contains(filter, ignoreCase = true)) {
                            fun choose() {
                                if (multiple) selected = if (index in selected) selected - index else selected + index
                                else onResult(index)
                            }
                            Surface(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { choose() },
                                color = if (index in selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (multiple) Checkbox(index in selected, { choose() })
                                    Column {
                                        Text(label)
                                        if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = {
            if (multiple) TextButton(onClick = { onResult(JSONArray(selected.sorted())) }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { onResult(null) }) { Text("取消") } })
}

@Composable
internal fun OfficialTextDialog(params: JSONObject, onDismiss: () -> Unit) {
    CodexDialog(onDismissRequest = onDismiss, title = { Text(params.optString("title").ifBlank { "文档预览" }) },
        text = { SelectionContainer {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(params.optString("text"), style = MonoTextStyle)
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
