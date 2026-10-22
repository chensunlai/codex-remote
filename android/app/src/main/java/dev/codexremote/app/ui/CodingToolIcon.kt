package dev.codexremote.app.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.codexremote.app.R
import dev.codexremote.app.model.CodingTool

/** Official plugin marks; only the monochrome Codex mark follows the host theme. */
@Composable
internal fun CodingToolIcon(
    toolId: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val claude = toolId == CodingTool.CLAUDE.id
    Icon(
        painter = painterResource(if (claude) R.drawable.ic_claude else R.drawable.ic_codex),
        contentDescription = contentDescription,
        modifier = modifier.size(20.dp),
        tint = if (claude) Color.Unspecified else LocalContentColor.current,
    )
}
