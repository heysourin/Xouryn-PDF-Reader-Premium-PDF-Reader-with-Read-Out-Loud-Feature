package com.novapdf.reader.ui.viewer

import android.graphics.Bitmap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novapdf.reader.model.Annotation
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.PageDimension
import com.novapdf.reader.model.SpeechPosition
import com.novapdf.reader.model.ToolType
import com.novapdf.reader.ui.annotations.AnnotationOverlayCanvas

@Composable
fun PageCanvasView(
    pageIndex: Int,
    dimension: PageDimension?,
    bitmap: Bitmap?,
    annotations: List<Annotation>,
    activeTool: ToolType,
    currentColor: Color,
    currentStrokeWidth: Float,
    currentAlpha: Float,
    speechPosition: SpeechPosition?,
    onAnnotationAdded: (Annotation) -> Unit,
    onEraseRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    onTapToReadRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val aspectRatio = dimension?.aspectRatio ?: (1f / 1.414f) // Default standard A4 ratio

    // Pulse animation for ReadEra TTS active line highlight
    val infiniteTransition = rememberInfiniteTransition(label = "tts_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .shadow(4.dp, RoundedCornerShape(4.dp)),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. PDF Rendered Bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            // 2. ReadEra-Style Glowing TTS Live Speech Highlight Overlay
            if (speechPosition != null && speechPosition.pageIndex == pageIndex) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val pageWidth = size.width
                    val pageHeight = size.height

                    val lineBounds = speechPosition.activeLineBounds.ifEmpty {
                        speechPosition.currentSentence?.bounds?.let { listOf(it) } ?: emptyList()
                    }

                    lineBounds.forEach { normRect ->
                        val left = normRect.left * pageWidth
                        val top = normRect.top * pageHeight
                        val width = normRect.width * pageWidth
                        val height = normRect.height * pageHeight

                        // Glowing yellow / amber background
                        drawRoundRect(
                            color = Color(0xFFFFEB3B).copy(alpha = pulseAlpha),
                            topLeft = Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                            size = Size(width + 4.dp.toPx(), height + 4.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )

                        // Outline border
                        drawRoundRect(
                            color = Color(0xFFF57F17).copy(alpha = 0.8f),
                            topLeft = Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                            size = Size(width + 4.dp.toPx(), height + 4.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }
            }

            // 3. Vector Annotation Drawing Layer
            AnnotationOverlayCanvas(
                pageIndex = pageIndex,
                annotations = annotations,
                activeTool = activeTool,
                currentColor = currentColor,
                currentStrokeWidth = currentStrokeWidth,
                currentAlpha = currentAlpha,
                onAnnotationAdded = onAnnotationAdded,
                onEraseRequested = { x, y ->
                    // Normalize coordinates
                    val normX = if (dimension != null && dimension.width > 0) x / dimension.width else 0.5f
                    val normY = if (dimension != null && dimension.height > 0) y / dimension.height else 0.5f
                    onEraseRequested(normX, normY)
                },
                onTapToReadRequested = { x, y ->
                    val normX = if (dimension != null && dimension.width > 0) x / dimension.width else 0.5f
                    val normY = if (dimension != null && dimension.height > 0) y / dimension.height else 0.5f
                    onTapToReadRequested(normX, normY)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 4. Subtle Page Number Pill
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${pageIndex + 1}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White
                )
            }
        }
    }
}

