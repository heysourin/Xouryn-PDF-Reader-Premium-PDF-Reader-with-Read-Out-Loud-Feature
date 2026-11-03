package com.novapdf.reader.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun VerticalFastScroller(
    currentPage: Int,
    pageCount: Int,
    onScrolledToPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pageCount <= 1) return

    val density = LocalDensity.current
    var isDragging by remember { mutableStateOf(false) }
    var dragYOffset by remember { mutableFloatStateOf(0f) }

    val handleHeightDp = 48.dp
    val handleWidthDp = 26.dp
    val handleHeightPx = with(density) { handleHeightDp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 40.dp)
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat()
        val availableTrack = (trackHeightPx - handleHeightPx).coerceAtLeast(1f)

        // Calculate Y position based on current page fraction or active drag
        val currentFraction = currentPage.toFloat() / (pageCount - 1).coerceAtLeast(1)
        val computedY = if (isDragging) {
            dragYOffset.coerceIn(0f, availableTrack)
        } else {
            (currentFraction * availableTrack).coerceIn(0f, availableTrack)
        }

        val displayPage = if (isDragging) {
            val frac = (computedY / availableTrack).coerceIn(0f, 1f)
            (frac * (pageCount - 1)).roundToInt()
        } else {
            currentPage
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            // Floating Page Preview Bubble (appears while scrubbing)
            AnimatedVisibility(
                visible = isDragging,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            x = with(density) { (-handleWidthDp - 14.dp).roundToPx() },
                            y = (computedY + handleHeightPx / 2f - with(density) { 16.dp.toPx() }).toInt()
                        )
                    }
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xEE1E1E1E),
                    shadowElevation = 8.dp
                ) {
                    Text(
                        text = "Page ${displayPage + 1} of $pageCount",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // Draggable Fast-Scroll Handle Pill (Xodo-style with ::: icon)
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = if (isDragging) MaterialTheme.colorScheme.primary else Color(0xDD2A2A2A),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, computedY.toInt()) }
                    .size(handleWidthDp, handleHeightDp)
                    .pointerInput(pageCount, availableTrack) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                dragYOffset = computedY + offset.y - handleHeightPx / 2f
                                val frac = (dragYOffset / availableTrack).coerceIn(0f, 1f)
                                val target = (frac * (pageCount - 1)).roundToInt()
                                onScrolledToPage(target)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragYOffset = (dragYOffset + dragAmount.y).coerceIn(0f, availableTrack)
                                val frac = (dragYOffset / availableTrack).coerceIn(0f, 1f)
                                val target = (frac * (pageCount - 1)).roundToInt()
                                onScrolledToPage(target)
                            },
                            onDragEnd = {
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Fast Scroll",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
