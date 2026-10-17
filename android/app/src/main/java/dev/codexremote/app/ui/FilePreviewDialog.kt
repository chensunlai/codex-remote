package dev.codexremote.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.codexremote.app.data.PreviewContent
import dev.codexremote.app.model.FilePreview

@Composable
internal fun FilePreviewDialog(preview: FilePreview, onDismiss: () -> Unit) {
    CodexDialog(
        onDismissRequest = onDismiss,
        title = { Text(preview.name, maxLines = 1) },
        text = {
            when (val content = preview.content) {
                is PreviewContent.Image -> {
                    var scale by remember(preview) { mutableStateOf(1f) }
                    var offset by remember(preview) { mutableStateOf(Offset.Zero) }
                    Column {
                        Image(content.bitmap.asImageBitmap(), contentDescription = preview.name,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 480.dp)
                                .clipToBounds().pointerInput(preview) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 5f)
                                        val maxX = size.width * (scale - 1) / 2
                                        val maxY = size.height * (scale - 1) / 2
                                        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                                    }
                                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                        Text("双指缩放，拖动查看", modifier = Modifier.padding(top = 8.dp))
                    }
                }
                is PreviewContent.Text -> Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(content.text, style = MonoTextStyle) }
                    if (content.truncated) Text("预览内容已截断", modifier = Modifier.padding(top = 12.dp))
                }
                is PreviewContent.Unsupported -> Text(content.reason)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
