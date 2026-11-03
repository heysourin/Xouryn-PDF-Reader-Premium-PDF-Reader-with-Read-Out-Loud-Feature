package com.novapdf.reader.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun ZoomableContainer(
    enabled: Boolean = true,
    minScale: Float = 1.0f,
    maxScale: Float = 6.0f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val state = rememberTransformableState { zoomChange, panChange, _ ->
        if (enabled) {
            val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
            val newOffset = if (newScale > 1f) {
                offset + panChange
            } else {
                Offset.Zero
            }
            scale = newScale
            offset = newOffset
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                offset = Offset(
                                    x = -(tapOffset.x - size.width / 2f),
                                    y = -(tapOffset.y - size.height / 2f)
                                )
                            }
                        }
                    )
                }
            }
            .transformable(state = state, enabled = enabled)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) {
        content()
    }
}

