package dev.codexremote.app.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.codexremote.app.model.ChatMessage
import dev.codexremote.app.model.FileChangeSummary
import dev.codexremote.app.model.MessageRole
import dev.codexremote.app.model.PlanStep
import java.util.Locale

@Composable
fun ChatMessageRow(message: ChatMessage, modifier: Modifier = Modifier) {
    when (message.role) {
        MessageRole.USER -> UserMessage(message, modifier)
        MessageRole.ASSISTANT -> AssistantMessage(message, modifier)
        MessageRole.SYSTEM, MessageRole.TOOL -> ActivityMessage(message, modifier)
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
private fun ActivityMessage(message: ChatMessage, modifier: Modifier) {
    val initiallyExpanded = message.status == "failed" || message.status == "declined"
    var expanded by remember(message.id) { mutableStateOf(initiallyExpanded) }
    val icon = activityIcon(message.kind)
    val tint = statusTint(message.status)
    val expandable = message.detail?.isNotBlank() == true || message.changes.isNotEmpty() ||
        (message.cwd?.isNotBlank() == true && message.kind == "commandExecution")

    Column(modifier.animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = message.title ?: activityTitle(message.kind),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = activitySummary(message),
                    style = if (message.kind == "commandExecution") MonoTextStyle else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起详情" else "展开详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp),
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
        shape = RoundedCornerShape(10.dp),
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
                            "completed" -> Icons.Outlined.CheckCircle
                            "inProgress", "in_progress" -> Icons.Outlined.HourglassEmpty
                            else -> Icons.Outlined.Info
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
            Icon(Icons.Outlined.Difference, contentDescription = null, modifier = Modifier.size(17.dp))
            Text("本轮差异", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起差异" else "展开差异",
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp),
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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
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
    "commandExecution" -> Icons.Outlined.Terminal
    "fileChange" -> Icons.Outlined.Difference
    "reasoning", "plan" -> Icons.Outlined.Psychology
    "webSearch" -> Icons.Outlined.Public
    "collabAgentToolCall", "subAgentActivity" -> Icons.Outlined.AccountTree
    "imageView", "imageGeneration" -> Icons.Outlined.Image
    "sleep" -> Icons.Outlined.HourglassEmpty
    "error" -> Icons.Outlined.ErrorOutline
    "contextCompaction" -> Icons.Outlined.Code
    else -> Icons.Outlined.Build
}

private fun activityTitle(kind: String?): String = when (kind) {
    "commandExecution" -> "命令"
    "fileChange" -> "文件变更"
    "reasoning" -> "分析"
    "plan" -> "计划"
    "webSearch" -> "网页搜索"
    "error" -> "执行失败"
    else -> "Codex 活动"
}

private fun activitySummary(message: ChatMessage): String = when {
    message.kind == "commandExecution" -> message.text.ifBlank { statusLabel(message.status) }
    message.changes.isNotEmpty() -> message.changes.joinToString(" · ") { it.path.substringAfterLast('/') }
    message.text.isNotBlank() -> message.text
    else -> statusLabel(message.status)
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
    durationMs < 1_000 -> "${durationMs}ms"
    durationMs < 60_000 -> String.format(Locale.US, "%.1fs", durationMs / 1_000.0)
    else -> String.format(Locale.US, "%dm %02ds", durationMs / 60_000, durationMs / 1_000 % 60)
}

private fun changeKindLabel(kind: String): String = when (kind.lowercase()) {
    "add", "added", "create" -> "新增"
    "delete", "deleted", "remove" -> "删除"
    "rename", "renamed" -> "重命名"
    else -> "修改"
}
