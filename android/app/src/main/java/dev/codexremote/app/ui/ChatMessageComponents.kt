package dev.codexremote.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.FileChangeSummary
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.PlanStep
import dev.codexremote.app.model.TurnSummary
import java.util.Locale

@Composable
fun ChatMessageRow(message: ChatMessage, modifier: Modifier = Modifier) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, modifier)
        MessageRole.ASSISTANT -> AssistantMessage(message, modifier)
        MessageRole.SYSTEM, MessageRole.TOOL -> RememberedActivityMessage(message, modifier)
    }
}

@Composable
private fun UserMessage(message: ChatMessage, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage, modifier: Modifier) {
    Column(modifier) {
        if (message.phase == "commentary") {
            Text(
                text = "进度更新",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (message.text.isNotBlank()) {
            SelectionContainer {
                Markdown(
                    content = message.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (message.status == "failed") {
            Text(
                text = "生成失败",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun ActivityGroup(
    messages: List<ChatMessage>,
    turn: TurnSummary?,
    isLiveActivity: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    itemExpanded: (ChatMessage) -> Boolean,
    onItemExpandedChange: (ChatMessage, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) return
    val running = isLiveActivity
    val failed = turn?.status == "failed" || messages.any { it.status == "failed" }

    Column(modifier.animateContentSize()) {
        if (!running) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clickable { onExpandedChange(!expanded) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    if (failed) CodexIcons.Error else CodexIcons.CheckCircle,
                    contentDescription = null,
                    tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (failed) {
                            "执行失败"
                        } else {
                            turn?.durationMs?.takeIf { it > 0 }?.let { "已处理 ${formatDuration(it)}" }
                                ?: "已完成"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    activityGroupSummary(messages).takeIf { it != "已完成" }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    if (expanded) CodexIcons.ChevronUp else CodexIcons.ChevronDown,
                    contentDescription = if (expanded) "收起执行过程" else "展开执行过程",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (running || expanded) {
            Column(
                modifier = Modifier.padding(start = if (running) 0.dp else 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                messages.forEach { message ->
                    ActivityMessage(
                        message = message,
                        expanded = itemExpanded(message),
                        onExpandedChange = { onItemExpandedChange(message, it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    ThinkingShimmer(
        text = "正在思考",
        modifier = modifier.padding(vertical = 7.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ThinkingShimmer(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val transition = rememberInfiniteTransition(label = "thinking-shimmer")
    val position by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 4_000
                -1f at 0
                -1f at 600
                2f at 1_600
                2f at 4_000
            },
        ),
        label = "thinking-shimmer-position",
    )
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.onSurface
    val offset = position * 180f
    val brush = Brush.linearGradient(
        colors = listOf(base, base, highlight, base, base),
        start = Offset(offset, 0f),
        end = Offset(offset + 180f, 0f),
    )
    Text(text = text, modifier = modifier, style = style.merge(TextStyle(brush = brush)))
}

@Composable
private fun RememberedActivityMessage(message: ChatMessage, modifier: Modifier) {
    var expanded by remember(message.id) {
        mutableStateOf(
            defaultActivityItemExpanded(message.status.isRunningStatus(), message.kind),
        )
    }
    ActivityMessage(
        message = message,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    )
}

@Composable
private fun ActivityMessage(
    message: ChatMessage,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val icon = activityIcon(message.kind)
    val tint = statusTint(message.status)
    val running = message.status.isRunningStatus()
    val expandable = message.detail?.isNotBlank() == true || message.changes.isNotEmpty() ||
        (message.cwd?.isNotBlank() == true && message.kind == "commandExecution")

    Column(modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clickable(enabled = expandable) { onExpandedChange(!expanded) }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            Box(Modifier.weight(1f)) {
                val label = activityLabel(message)
                if (running) {
                    ThinkingShimmer(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (message.status == "failed" || message.status == "declined") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = if (expanded) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            message.durationMs?.takeIf { it > 0 }?.let {
                Text(
                    formatDuration(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (expandable) {
                Icon(
                    if (expanded) CodexIcons.ChevronUp else CodexIcons.ChevronDown,
                    contentDescription = if (expanded) "收起详情" else "展开详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                ActivityDetails(message)
            }
        }
    }
}

@Composable
private fun ActivityDetails(message: ChatMessage) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        message.cwd?.takeIf(String::isNotBlank)?.let { cwd ->
            Text(cwd, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (message.kind == "commandExecution" && message.text.isNotBlank()) {
            CodeBlock(message.text)
        } else if (message.text.isNotBlank() && message.changes.isEmpty()) {
            SelectionContainer {
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        message.detail?.takeIf(String::isNotBlank)?.let { detail -> CodeBlock(detail) }
        message.changes.forEach { change -> FileChangeBlock(change) }
        message.exitCode?.let { exitCode ->
            Text(
                text = "退出码 $exitCode",
                style = MaterialTheme.typography.labelMedium,
                color = if (exitCode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FileChangeBlock(change: FileChangeSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = changeKindLabel(change.kind),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = change.path,
                style = MonoTextStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (change.diff.isNotBlank()) DiffBlock(change.diff)
    }
}

@Composable
fun PlanPanel(
    explanation: String?,
    steps: List<PlanStep>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("执行计划", style = MaterialTheme.typography.titleMedium)
            explanation?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(
                        imageVector = when (step.status) {
                            "completed" -> CodexIcons.CheckCircle
                            "inProgress", "in_progress" -> CodexIcons.Hourglass
                            else -> CodexIcons.Info
                        },
                        contentDescription = null,
                        tint = statusTint(step.status),
                        modifier = Modifier.size(18.dp),
                    )
                    Text(step.step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun UnifiedDiffPanel(diff: String, modifier: Modifier = Modifier) {
    if (diff.isBlank()) return
    var expanded by remember(diff) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(CodexIcons.FileDiff, contentDescription = null, modifier = Modifier.size(17.dp))
            Text("本轮差异", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) CodexIcons.ChevronUp else CodexIcons.ChevronDown,
                contentDescription = if (expanded) "收起差异" else "展开差异",
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Box(Modifier.padding(10.dp)) { DiffBlock(diff) }
            }
        }
    }
}

@Composable
private fun CodeBlock(content: String) {
    val shown = content.lineSequence().take(800).joinToString("\n")
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        SelectionContainer { Text(shown, style = MonoTextStyle) }
    }
}

@Composable
private fun DiffBlock(diff: String) {
    val lines = remember(diff) { diff.lineSequence().take(600).toList() }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        SelectionContainer {
            Column {
                lines.forEach { line ->
                    val background = when {
                        line.startsWith("+") && !line.startsWith("+++") -> Color(0x1F2E7D32)
                        line.startsWith("-") && !line.startsWith("---") -> Color(0x1FC62828)
                        line.startsWith("@@") -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        else -> Color.Transparent
                    }
                    Text(
                        text = line.ifEmpty { " " },
                        style = MonoTextStyle,
                        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (diff.lineSequence().count() > lines.size) {
            Spacer(Modifier.height(4.dp))
            Text(
                "差异过长，仅显示前 ${lines.size} 行",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

private fun activityIcon(kind: String?): ImageVector = when (kind) {
    "commandExecution" -> CodexIcons.Terminal
    "fileChange" -> CodexIcons.FileDiff
    "reasoning", "plan" -> CodexIcons.Brain
    "webSearch" -> CodexIcons.Globe
    "collabAgentToolCall", "subAgentActivity" -> CodexIcons.Network
    "imageView", "imageGeneration" -> CodexIcons.Image
    "sleep" -> CodexIcons.Hourglass
    "error" -> CodexIcons.Error
    "contextCompaction" -> CodexIcons.Code
    else -> CodexIcons.Wrench
}

private fun activityLabel(message: ChatMessage): String {
    val running = message.status.isRunningStatus()
    return when (message.kind) {
        "commandExecution" -> commandActivityLabel(message, running)
        "fileChange" -> when {
            running -> "正在编辑文件"
            message.status == "failed" -> "文件编辑失败"
            message.status == "declined" -> "未编辑文件"
            message.changes.size == 1 -> "已编辑 ${message.changes.single().path.substringAfterLast('/')}"
            message.changes.isNotEmpty() -> "已编辑 ${message.changes.size} 个文件"
            else -> "已编辑文件"
        }
        "webSearch" -> when {
            running && message.text.isNotBlank() -> "正在网络上搜索 ${message.text}"
            running -> "正在搜索网页"
            message.text.isNotBlank() -> "已搜索网页：${message.text}"
            else -> "已搜索网页"
        }
        "reasoning" -> when {
            running && message.text.isBlank() -> "正在思考"
            running && message.text == "正在分析" -> "正在思考"
            message.text.isNotBlank() -> message.text
            else -> if (running) "正在思考" else "已完成分析"
        }
        "plan" -> message.text.ifBlank { if (running) "正在规划" else "已完成计划" }
        "contextCompaction" -> if (running) "正在压缩上下文" else "上下文已压缩"
        "imageView" -> if (running) "正在查看 ${message.text}" else "已查看 ${message.text}"
        "imageGeneration" -> if (running) "正在生成图片" else "已生成图片"
        "sleep" -> if (running) "正在等待" else "等待结束"
        "error" -> message.text.ifBlank { "执行失败" }
        "agentMessage" -> message.text.ifBlank { if (running) "正在处理" else "已完成" }
        else -> when {
            message.status == "failed" -> message.text.ifBlank { "工具调用失败" }
            running -> message.text.ifBlank { "正在调用工具" }
            else -> message.text.ifBlank { message.title ?: "已调用工具" }
        }
    }
}

private fun commandActivityLabel(message: ChatMessage, running: Boolean): String {
    if (message.status == "failed") {
        return "命令失败${message.commandSuffix()}"
    }
    if (message.status == "declined") {
        return "未运行${message.commandSuffix()}"
    }
    val action = message.commandActions.firstOrNull { it.type != "unknown" }
    if (action != null) {
        val target = action.path?.takeIf(String::isNotBlank)
            ?: action.name?.takeIf(String::isNotBlank)
            ?: action.query?.takeIf(String::isNotBlank)
        return when (action.type) {
            "read" -> listOf(if (running) "正在读取" else "已读取", target).filterNotNull().joinToString(" ")
            "listFiles" -> if (target == null) {
                if (running) "正在列出文件" else "已列出文件"
            } else {
                "${if (running) "正在列出" else "已列出"} $target 中的文件"
            }
            "search" -> when {
                action.query?.isNotBlank() == true ->
                    "${if (running) "正在搜索" else "已搜索"} ${action.query}"
                target != null -> "${if (running) "正在搜索" else "已搜索"} $target 中的文件"
                else -> if (running) "正在搜索文件" else "已搜索文件"
            }
            else -> "${if (running) "正在运行" else "已运行"}${message.commandSuffix()}"
        }
    }
    return "${if (running) "正在运行" else "已运行"}${message.commandSuffix()}"
}

private fun ChatMessage.commandSuffix(): String {
    val command = text.lineSequence().firstOrNull()?.trim().orEmpty()
    return command.takeIf(String::isNotBlank)?.let { " ${it.take(180)}" }.orEmpty()
}

private fun activityGroupSummary(messages: List<ChatMessage>): String {
    if (messages.any { it.status == "failed" }) return "执行过程包含失败项"
    val summaries = buildList {
        if (messages.any { it.kind == "commandExecution" }) add("运行了命令")
        val editedFiles = messages
            .filter { it.kind == "fileChange" }
            .flatMap(ChatMessage::changes)
            .map { it.path }
            .distinct()
            .size
        if (editedFiles == 1) add("编辑了一个文件")
        if (editedFiles > 1) add("编辑了多个文件")
        if (messages.any { it.kind == "webSearch" }) add("已搜索网页")
        if (messages.any { it.kind == "contextCompaction" }) add("已压缩上下文")
        val tools = messages.count {
            it.kind in setOf("mcpToolCall", "dynamicToolCall", "collabAgentToolCall", "subAgentActivity")
        }
        if (tools > 0) add("调用了工具")
    }
    return summaries.joinToString(" · ").ifBlank { "已完成" }
}

private fun statusLabel(status: String?): String = when (status) {
    "inProgress", "in_progress", "active" -> "正在执行"
    "completed", "applied", "success" -> "已完成"
    "failed" -> "失败"
    "declined" -> "已拒绝"
    else -> status.orEmpty()
}

@Composable
private fun statusTint(status: String?): Color = when (status) {
    "failed", "declined" -> MaterialTheme.colorScheme.error
    "inProgress", "in_progress", "active" -> MaterialTheme.colorScheme.primary
    "completed", "applied", "success" -> Color(0xFF2E7D32)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatDuration(durationMs: Long): String = when {
    durationMs < 1_000 -> "${durationMs} 毫秒"
    durationMs < 60_000 -> String.format(Locale.US, "%.1f 秒", durationMs / 1_000.0)
    durationMs < 3_600_000 -> String.format(
        Locale.US,
        "%d 分 %02d 秒",
        durationMs / 60_000,
        durationMs / 1_000 % 60,
    )
    else -> String.format(
        Locale.US,
        "%d 小时 %02d 分 %02d 秒",
        durationMs / 3_600_000,
        durationMs / 60_000 % 60,
        durationMs / 1_000 % 60,
    )
}

private fun changeKindLabel(kind: String): String = when (kind.lowercase()) {
    "add", "added", "create" -> "新增"
    "delete", "deleted", "remove" -> "删除"
    "rename", "renamed" -> "重命名"
    else -> "修改"
}
