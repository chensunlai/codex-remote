package dev.codexremote.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.codexremote.app.model.AccountRateLimits
import dev.codexremote.app.model.ModelOption
import dev.codexremote.app.model.PermissionProfile
import dev.codexremote.app.model.RemoteFileMatch
import dev.codexremote.app.model.RemoteFileType
import dev.codexremote.app.model.SkillOption
import dev.codexremote.app.model.ThreadTokenUsage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ComposerPanel {
    ADD,
    COMMANDS,
    MODELS,
    EFFORTS,
    PERMISSIONS,
    STATUS,
}

@Composable
internal fun ComposerPalette(
    panel: ComposerPanel,
    slashQuery: String,
    fileQuery: String,
    fileResults: List<RemoteFileMatch>,
    models: List<ModelOption>,
    selectedModel: String?,
    selectedEffort: String?,
    permissionProfiles: List<PermissionProfile>,
    selectedPermission: String?,
    skills: List<SkillOption>,
    threadId: String,
    tokenUsage: ThreadTokenUsage?,
    rateLimits: AccountRateLimits?,
    planAvailable: Boolean,
    planEnabled: Boolean,
    turnActive: Boolean,
    onBack: () -> Unit,
    onFileQuery: (String) -> Unit,
    onBrowseFiles: () -> Unit,
    onFile: (RemoteFileMatch) -> Unit,
    onGoal: () -> Unit,
    onTogglePlan: () -> Unit,
    onModel: (String) -> Unit,
    onEffort: (String) -> Unit,
    onPermission: (String) -> Unit,
    onSkill: (SkillOption) -> Unit,
    onNew: () -> Unit,
    onCompact: () -> Unit,
    onReview: () -> Unit,
    onOpenPanel: (ComposerPanel) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .testTag("composer-palette"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
    ) {
        when (panel) {
            ComposerPanel.ADD -> AddPalette(
                query = fileQuery,
                results = fileResults,
                planAvailable = planAvailable,
                planEnabled = planEnabled,
                onQuery = onFileQuery,
                onBrowseFiles = onBrowseFiles,
                onFile = onFile,
                onGoal = onGoal,
                onTogglePlan = onTogglePlan,
            )
            ComposerPanel.COMMANDS -> CommandPalette(
                query = slashQuery,
                models = models,
                selectedModel = selectedModel,
                selectedEffort = selectedEffort,
                selectedPermission = selectedPermission,
                skills = skills,
                planAvailable = planAvailable,
                planEnabled = planEnabled,
                turnActive = turnActive,
                onOpenPanel = onOpenPanel,
                onGoal = onGoal,
                onTogglePlan = onTogglePlan,
                onSkill = onSkill,
                onNew = onNew,
                onCompact = onCompact,
                onReview = onReview,
            )
            ComposerPanel.MODELS -> ModelPalette(models, selectedModel, onBack, onModel)
            ComposerPanel.EFFORTS -> EffortPalette(
                models.firstOrNull { it.id == selectedModel }?.efforts.orEmpty(),
                selectedEffort,
                onBack,
                onEffort,
            )
            ComposerPanel.PERMISSIONS -> PermissionPalette(
                permissionProfiles,
                selectedPermission,
                onBack,
                onPermission,
            )
            ComposerPanel.STATUS -> StatusPalette(threadId, tokenUsage, rateLimits, onBack)
        }
    }
}

@Composable
private fun AddPalette(
    query: String,
    results: List<RemoteFileMatch>,
    planAvailable: Boolean,
    planEnabled: Boolean,
    onQuery: (String) -> Unit,
    onBrowseFiles: () -> Unit,
    onFile: (RemoteFileMatch) -> Unit,
    onGoal: () -> Unit,
    onTogglePlan: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        item { PaletteSection("添加") }
        item {
            PaletteRow(
                icon = CodexIcons.Attach,
                title = "文件和文件夹",
                onClick = onBrowseFiles,
            )
        }
        item {
            PaletteRow(
                icon = CodexIcons.Goal,
                title = "目标",
                description = "设置要持续追求的目标",
                onClick = onGoal,
            )
        }
        if (planAvailable) {
            item {
                PaletteRow(
                    icon = CodexIcons.Lightbulb,
                    title = "计划模式",
                    description = if (planEnabled) "退出计划模式" else "开启计划模式",
                    selected = planEnabled,
                    onClick = onTogglePlan,
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 3.dp)) }
        item { PaletteSection("文件") }
        item {
            PaletteSearch(
                value = query,
                onValueChange = onQuery,
                placeholder = "输入内容搜索文件",
            )
        }
        items(results, key = RemoteFileMatch::path) { file ->
            PaletteRow(
                icon = if (file.type == RemoteFileType.DIRECTORY) CodexIcons.Folder else CodexIcons.Code,
                title = file.name,
                description = file.path,
                onClick = { onFile(file) },
            )
        }
    }
}

@Composable
private fun CommandPalette(
    query: String,
    models: List<ModelOption>,
    selectedModel: String?,
    selectedEffort: String?,
    selectedPermission: String?,
    skills: List<SkillOption>,
    planAvailable: Boolean,
    planEnabled: Boolean,
    turnActive: Boolean,
    onOpenPanel: (ComposerPanel) -> Unit,
    onGoal: () -> Unit,
    onTogglePlan: () -> Unit,
    onSkill: (SkillOption) -> Unit,
    onNew: () -> Unit,
    onCompact: () -> Unit,
    onReview: () -> Unit,
) {
    val normalized = query.trim().lowercase(Locale.ROOT)
    fun matches(vararg values: String): Boolean =
        normalized.isEmpty() || values.any { it.lowercase(Locale.ROOT).contains(normalized) }
    val selectedModelLabel = models.firstOrNull { it.id == selectedModel }?.displayName
        ?: selectedModel
        ?: "模型"

    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        if (matches("权限", "permission", "access")) {
            item {
                PaletteRow(
                    icon = CodexIcons.Shield,
                    title = "权限",
                    value = selectedPermission?.let(::permissionProfileLabel) ?: "选择权限",
                    onClick = { onOpenPanel(ComposerPanel.PERMISSIONS) },
                )
            }
        }
        if (matches("推理", "reasoning", "effort", selectedEffort.orEmpty())) {
            item {
                PaletteRow(
                    icon = CodexIcons.Brain,
                    title = "Reasoning",
                    value = selectedEffort ?: "medium",
                    onClick = { onOpenPanel(ComposerPanel.EFFORTS) },
                )
            }
        }
        if (matches("模型", "model", selectedModelLabel)) {
            item {
                PaletteRow(
                    icon = CodexIcons.ViewModel,
                    title = "模型",
                    value = selectedModelLabel,
                    onClick = { onOpenPanel(ComposerPanel.MODELS) },
                )
            }
        }
        if (matches("状态", "status", "usage", "limit")) {
            item {
                PaletteRow(
                    icon = CodexIcons.Status,
                    title = "状态",
                    description = "显示聊天 ID、上下文用量和速率限制",
                    onClick = { onOpenPanel(ComposerPanel.STATUS) },
                )
            }
        }
        if (matches("目标", "goal")) {
            item {
                PaletteRow(
                    icon = CodexIcons.Goal,
                    title = "目标",
                    description = "设置要持续追求的目标",
                    onClick = onGoal,
                )
            }
        }
        if (planAvailable && matches("计划模式", "plan")) {
            item {
                PaletteRow(
                    icon = CodexIcons.Lightbulb,
                    title = "计划模式",
                    description = if (planEnabled) "退出计划模式" else "开启计划模式",
                    selected = planEnabled,
                    onClick = onTogglePlan,
                )
            }
        }

        val showActions = matches("新建", "new", "压缩", "compact", "审阅", "review")
        if (showActions) item { PaletteSection("操作") }
        if (matches("新建", "new")) {
            item {
                PaletteRow(
                    icon = CodexIcons.MessageAdd,
                    title = "新建会话",
                    onClick = onNew,
                )
            }
        }
        if (!turnActive && matches("压缩", "compact")) {
            item {
                PaletteRow(
                    icon = CodexIcons.Compact,
                    title = "压缩上下文",
                    onClick = onCompact,
                )
            }
        }
        if (!turnActive && matches("审阅", "review")) {
            item {
                PaletteRow(
                    icon = CodexIcons.Review,
                    title = "审阅未提交更改",
                    onClick = onReview,
                )
            }
        }

        val matchingSkills = skills.filter { skill ->
            matches(skill.name, skill.displayName, skill.description)
        }
        if (matchingSkills.isNotEmpty()) item { PaletteSection("技能") }
        items(matchingSkills, key = SkillOption::path) { skill ->
            PaletteRow(
                icon = CodexIcons.ViewModel,
                title = skill.displayName,
                description = skill.description,
                value = "系统",
                onClick = { onSkill(skill) },
            )
        }
    }
}

@Composable
private fun ModelPalette(
    models: List<ModelOption>,
    selected: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(models, query) {
        val value = query.trim()
        if (value.isEmpty()) models else models.filter {
            it.id.contains(value, true) || it.displayName.contains(value, true)
        }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        item { PaletteHeader("模型", onBack) }
        if (models.size > 5) {
            item { PaletteSearch(query, { query = it }, "搜索模型") }
        }
        items(filtered, key = ModelOption::id) { model ->
            PaletteRow(
                icon = CodexIcons.ViewModel,
                title = model.displayName,
                description = model.id.takeUnless { it == model.displayName },
                selected = model.id == selected,
                onClick = { onSelect(model.id) },
            )
        }
    }
}

@Composable
private fun EffortPalette(
    efforts: List<String>,
    selected: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        item { PaletteHeader("Reasoning effort", onBack) }
        items(efforts, key = { it }) { effort ->
            PaletteRow(
                icon = CodexIcons.Brain,
                title = effort,
                selected = effort == selected,
                onClick = { onSelect(effort) },
            )
        }
    }
}

@Composable
private fun PermissionPalette(
    profiles: List<PermissionProfile>,
    selected: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val options = remember(profiles, selected) {
        if (selected.isNullOrBlank() || profiles.any { it.id == selected }) {
            profiles
        } else {
            listOf(PermissionProfile(selected, null, true)) + profiles
        }
    }
    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        item { PaletteHeader("权限", onBack) }
        items(options, key = PermissionProfile::id) { profile ->
            PaletteRow(
                icon = CodexIcons.Shield,
                title = permissionProfileLabel(profile.id),
                description = profile.description,
                selected = profile.id == selected,
                onClick = { onSelect(profile.id) },
            )
        }
    }
}

@Composable
private fun StatusPalette(
    threadId: String,
    usage: ThreadTokenUsage?,
    limits: AccountRateLimits?,
    onBack: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        item { PaletteHeader("状态", onBack) }
        item {
            PaletteRow(
                icon = CodexIcons.Code,
                title = "聊天 ID",
                description = threadId,
                onClick = null,
            )
        }
        item {
            val context = usage?.let {
                val remaining = 100 - ((it.usedTokens.toDouble() / it.contextWindow.coerceAtLeast(1)) * 100)
                    .roundToInt()
                    .coerceIn(0, 100)
                "$remaining% 剩余 · ${formatTokenCount(it.usedTokens)} / ${formatTokenCount(it.contextWindow)} tokens"
            } ?: "等待 Codex 返回上下文用量"
            PaletteRow(
                icon = CodexIcons.Status,
                title = "上下文",
                description = context,
                onClick = null,
            )
        }
        limits?.primary?.let { window ->
            item {
                PaletteRow(
                    icon = CodexIcons.Status,
                    title = "短期限制",
                    description = rateLimitDescription(window.usedPercent, window.windowDurationMins, window.resetsAt),
                    onClick = null,
                )
            }
        }
        limits?.secondary?.let { window ->
            item {
                PaletteRow(
                    icon = CodexIcons.Status,
                    title = "长期限制",
                    description = rateLimitDescription(window.usedPercent, window.windowDurationMins, window.resetsAt),
                    onClick = null,
                )
            }
        }
    }
}

@Composable
private fun PaletteHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(CodexIcons.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PaletteSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun PaletteSearch(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                CodexIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun PaletteRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    value: String? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val clickModifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            description?.takeIf(String::isNotBlank)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        value?.let {
            Spacer(Modifier.width(4.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                CodexIcons.Check,
                contentDescription = "已选择",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun permissionProfileLabel(id: String): String = when (id) {
    ":read-only" -> "只读"
    ":workspace" -> "工作区"
    ":full-access", ":danger-full-access" -> "完全访问"
    else -> id.removePrefix(":")
}

private fun rateLimitDescription(used: Double, duration: Long?, resetsAt: Long?): String {
    val remaining = (100 - used.roundToInt()).coerceIn(0, 100)
    val window = duration?.let { " · ${formatDuration(it)}" }.orEmpty()
    val reset = resetsAt?.takeIf { it > 0 }?.let {
        val time = Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
        " · ${time.format(DateTimeFormatter.ofPattern("M/d HH:mm"))} 重置"
    }.orEmpty()
    return "$remaining% 剩余$window$reset"
}

private fun formatDuration(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60L == 0L -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

private fun formatTokenCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
}
