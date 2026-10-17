package dev.codexremote.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Mount on first visit, then keep the same composition and viewport while hidden. */
@Composable
internal fun RetainedPane(visible: Boolean, content: @Composable () -> Unit) {
    var visited by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) visited = true }
    if (visible || visited) {
        Box(Modifier.fillMaxSize()
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    // Unplaced content cannot draw, receive touches or participate in focus traversal.
                    if (visible) placeable.placeRelative(0, 0)
                }
            }) { content() }
    }
}
