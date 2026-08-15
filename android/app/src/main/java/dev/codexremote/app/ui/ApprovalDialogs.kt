package dev.codexremote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.codexremote.app.model.PendingQuestion
import dev.codexremote.app.model.PendingRequest
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun PendingRequestDialog(
    pending: PendingRequest,
    onDecision: (String) -> Unit,
    onAnswers: (Map<String, List<String>>) -> Unit,
    onPermission: (permissionsJson: String, session: Boolean) -> Unit,
    onElicitation: (action: String, content: JSONObject?) -> Unit,
) {
    when {
        pending.questions.isNotEmpty() -> UserInputDialog(pending, onAnswers)
        pending.permissionsJson != null -> PermissionDialog(pending, onPermission)
        pending.elicitationMode != null -> ElicitationDialog(pending, onElicitation)
        else -> ApprovalDialog(pending, onDecision)
    }
}

@Composable
private fun ApprovalDialog(pending: PendingRequest, onDecision: (String) -> Unit) {
    val supportsSession = pending.method.contains("commandExecution") ||
        pending.method.contains("fileChange") || pending.method.contains("execCommand")
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(pending.title) },
        text = {
            SelectionContainer {
                Text(
                    pending.detail,
                    style = if (pending.detail.contains('\n')) MonoTextStyle else MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            Row {
                if (supportsSession) {
                    TextButton(onClick = { onDecision("acceptForSession") }) { Text("本会话允许") }
                }
                Button(onClick = { onDecision("accept") }) { Text("允许") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecision("decline") }) { Text("拒绝") }
        },
    )
}

@Composable
private fun UserInputDialog(
    pending: PendingRequest,
    onAnswers: (Map<String, List<String>>) -> Unit,
) {
    val values = remember(pending.requestId) { mutableStateMapOf<String, String>() }
    val other = remember(pending.requestId) { mutableStateMapOf<String, String>() }
    val complete = pending.questions.all { question ->
        val selected = values[question.id].orEmpty()
        selected.isNotBlank() && (selected != OTHER_VALUE || other[question.id].orEmpty().isNotBlank())
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(20.dp).heightIn(max = 720.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        ) {
            Column {
                Text(
                    pending.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(20.dp),
                )
                HorizontalDivider()
                LazyColumn(
                    Modifier.weight(1f, fill = false).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(pending.questions, key = PendingQuestion::id) { question ->
                        QuestionField(
                            question = question,
                            value = values[question.id].orEmpty(),
                            otherValue = other[question.id].orEmpty(),
                            onValue = { values[question.id] = it },
                            onOther = { other[question.id] = it },
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        enabled = complete,
                        onClick = {
                            onAnswers(
                                pending.questions.associate { question ->
                                    val selected = values[question.id].orEmpty()
                                    question.id to listOf(
                                        if (selected == OTHER_VALUE) other[question.id].orEmpty() else selected,
                                    )
                                },
                            )
                        },
                    ) { Text("提交") }
                }
            }
        }
    }
}

@Composable
private fun QuestionField(
    question: PendingQuestion,
    value: String,
    otherValue: String,
    onValue: (String) -> Unit,
    onOther: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (question.header.isNotBlank()) {
            Text(
                question.header,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(question.question, style = MaterialTheme.typography.bodyLarge)
        if (question.options.isEmpty()) {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
                visualTransformation = if (question.isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        } else {
            question.options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable { onValue(option.label) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = value == option.label, onClick = { onValue(option.label) })
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                        if (option.description.isNotBlank()) {
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (question.isOther) {
                Row(
                    Modifier.fillMaxWidth().clickable { onValue(OTHER_VALUE) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == OTHER_VALUE, onClick = { onValue(OTHER_VALUE) })
                    Text("其他")
                }
                if (value == OTHER_VALUE) {
                    OutlinedTextField(
                        value = otherValue,
                        onValueChange = onOther,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        visualTransformation = if (question.isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDialog(
    pending: PendingRequest,
    onPermission: (permissionsJson: String, session: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(pending.title) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(pending.detail, style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(pending.permissionsJson.orEmpty().prettyJson(), style = MonoTextStyle)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { onPermission(pending.permissionsJson.orEmpty(), true) }) {
                    Text("本会话允许")
                }
                Button(onClick = { onPermission(pending.permissionsJson.orEmpty(), false) }) { Text("本轮允许") }
            }
        },
        dismissButton = {
            TextButton(onClick = { onPermission("{}", false) }) { Text("拒绝") }
        },
    )
}

@Composable
private fun ElicitationDialog(
    pending: PendingRequest,
    onElicitation: (action: String, content: JSONObject?) -> Unit,
) {
    val params = remember(pending.requestId) {
        runCatching { JSONObject(pending.paramsJson) }.getOrElse { JSONObject() }
    }
    val mode = params.optString("mode")
    val schema = params.optJSONObject("requestedSchema") ?: JSONObject()
    val fields = remember(pending.requestId) { parseElicitationFields(schema) }
    val values = remember(pending.requestId) {
        mutableStateMapOf<String, String>().also { map ->
            fields.forEach { field -> map[field.name] = field.defaultValue }
        }
    }
    val required = remember(pending.requestId) { schema.optJSONArray("required")?.stringValues()?.toSet().orEmpty() }
    val uriHandler = LocalUriHandler.current
    val url = params.optString("url")
    val complete = fields.all { it.name !in required || values[it.name].orEmpty().isNotBlank() }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(params.optString("serverName", "工具确认")) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(params.optString("message", pending.detail))
                if (mode == "url" && url.isNotBlank()) {
                    TextButton(onClick = { uriHandler.openUri(url) }) { Text("打开链接") }
                }
                fields.forEach { field ->
                    ElicitationField(field, values[field.name].orEmpty()) { values[field.name] = it }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = mode == "url" || complete,
                onClick = {
                    val content = if (mode == "url") null else JSONObject().also { output ->
                        fields.forEach { field -> output.put(field.name, field.convert(values[field.name].orEmpty())) }
                    }
                    onElicitation("accept", content)
                },
            ) { Text("确认") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onElicitation("cancel", null) }) { Text("取消") }
                TextButton(onClick = { onElicitation("decline", null) }) { Text("拒绝") }
            }
        },
    )
}

@Composable
private fun ElicitationField(field: ElicitationField, value: String, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.title.ifBlank { field.name }, style = MaterialTheme.typography.labelLarge)
        if (field.description.isNotBlank()) {
            Text(field.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            field.type == "boolean" -> Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (value.toBoolean()) "开启" else "关闭", Modifier.weight(1f))
                Switch(checked = value.toBoolean(), onCheckedChange = { onValue(it.toString()) })
            }
            field.options.isNotEmpty() -> field.options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable { onValue(option) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == option, onClick = { onValue(option) })
                    Text(option)
                }
            }
            else -> OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                singleLine = field.type == "number" || field.type == "integer",
                visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        }
    }
}

private data class ElicitationField(
    val name: String,
    val title: String,
    val description: String,
    val type: String,
    val options: List<String>,
    val defaultValue: String,
    val secret: Boolean,
) {
    fun convert(value: String): Any = when (type) {
        "boolean" -> value.toBoolean()
        "integer" -> value.toLongOrNull() ?: value
        "number" -> value.toDoubleOrNull() ?: value
        else -> value
    }
}

private fun parseElicitationFields(schema: JSONObject): List<ElicitationField> {
    val properties = schema.optJSONObject("properties") ?: return emptyList()
    return buildList {
        val names = properties.keys()
        while (names.hasNext()) {
            val name = names.next()
            val field = properties.optJSONObject(name) ?: continue
            add(
                ElicitationField(
                    name = name,
                    title = field.optString("title"),
                    description = field.optString("description"),
                    type = field.optString("type", "string"),
                    options = field.optJSONArray("enum")?.stringValues().orEmpty(),
                    defaultValue = field.opt("default")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
                    secret = field.optString("format") == "password" || field.optBoolean("writeOnly"),
                ),
            )
        }
    }
}

private fun JSONArray.stringValues(): List<String> = buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun String.prettyJson(): String = runCatching { JSONObject(this).toString(2) }.getOrDefault(this)

private const val OTHER_VALUE = "__other__"
