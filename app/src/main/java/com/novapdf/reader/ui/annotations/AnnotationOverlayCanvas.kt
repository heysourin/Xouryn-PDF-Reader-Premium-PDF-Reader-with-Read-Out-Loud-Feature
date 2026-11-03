package com.novapdf.reader.ui.annotations

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import com.novapdf.reader.model.Annotation
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.StrokePoint
import com.novapdf.reader.model.ToolType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AnnotationOverlayCanvas(
    pageIndex: Int,
    annotations: List<Annotation>,
    activeTool: ToolType,
    currentColor: Color,
    currentStrokeWidth: Float,
    currentAlpha: Float,
    onAnnotationAdded: (Annotation) -> Unit,
    onEraseRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    onTapToReadRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeStrokePoints = remember { mutableStateListOf<StrokePoint>() }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                if (activeTool == ToolType.PAN || activeTool == ToolType.TEXT_SELECT || activeTool == ToolType.TTS_READER) return@pointerInteropFilter false

                val width = event.source.let { 1f } // Handled via Canvas size
                val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS

                // Eraser tool
                if (activeTool == ToolType.ERASER) {
                    if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onEraseRequested(event.x, event.y)
                    }
                    return@pointerInteropFilter true
                }

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activeStrokePoints.clear()
                        activeStrokePoints.add(
                            StrokePoint(
                                x = event.x,
                                y = event.y,
                                pressure = if (isStylus) event.pressure else 1.0f
                            )
                        )
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val historySize = event.historySize
                        for (i in 0 until historySize) {
                            activeStrokePoints.add(
                                StrokePoint(
                                    x = event.getHistoricalX(i),
                                    y = event.getHistoricalY(i),
                                    pressure = if (isStylus) event.getHistoricalPressure(i) else 1.0f
                                )
                            )
                        }
                        activeStrokePoints.add(
                            StrokePoint(
                                x = event.x,
                                y = event.y,
                                pressure = if (isStylus) event.pressure else 1.0f
                            )
                        )
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (activeStrokePoints.isNotEmpty()) {
                            val annot = createAnnotationFromStroke(
                                pageIndex = pageIndex,
                                activeTool = activeTool,
                                points = activeStrokePoints.toList(),
                                color = currentColor,
                                strokeWidth = currentStrokeWidth,
                                alpha = currentAlpha
                            )
                            if (annot != null) {
                                onAnnotationAdded(annot)
                            }
                            activeStrokePoints.clear()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        activeStrokePoints.clear()
                        true
                    }
                    else -> false
                }
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 1. Draw existing committed annotations
        annotations.forEach { annot ->
            drawCommittedAnnotation(annot, canvasWidth, canvasHeight)
        }

        // 2. Draw currently active in-progress stroke
        if (activeStrokePoints.isNotEmpty()) {
            drawActiveStroke(
                activeTool = activeTool,
                points = activeStrokePoints,
                color = currentColor,
                strokeWidth = currentStrokeWidth,
                alpha = currentAlpha
            )
        }
    }
}

private fun DrawScope.drawCommittedAnnotation(
    annot: Annotation,
    pageWidth: Float,
    pageHeight: Float
) {
    val color = Color(annot.colorArgb).copy(alpha = annot.alpha)

    when (annot) {
        is Annotation.Ink -> {
            if (annot.points.size > 1) {
                val path = buildSmoothedPath(annot.points)
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = annot.strokeWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
        is Annotation.Highlight -> {
            // Text highlighter snapped rects
            annot.rects.forEach { rect ->
                val composeRect = rect.toComposeRect(pageWidth, pageHeight)
                drawRect(
                    color = color,
                    topLeft = composeRect.topLeft,
                    size = composeRect.size,
                    blendMode = BlendMode.Multiply
                )
            }
            // Freehand highlight
            if (annot.freehandPoints.size > 1) {
                val path = buildSmoothedPath(annot.freehandPoints)
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = annot.strokeWidth,
                        cap = StrokeCap.Square,
                        join = StrokeJoin.Round
                    ),
                    blendMode = BlendMode.Multiply
                )
            }
        }
        is Annotation.Shape -> {
            val start = Offset(annot.startPoint.x, annot.startPoint.y)
            val end = Offset(annot.endPoint.x, annot.endPoint.y)
            when (annot.shapeType) {
                ToolType.RECTANGLE -> {
                    val left = minOf(start.x, end.x)
                    val top = minOf(start.y, end.y)
                    val width = kotlin.math.abs(end.x - start.x)
                    val height = kotlin.math.abs(end.y - start.y)
                    drawRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = annot.strokeWidth)
                    )
                }
                ToolType.OVAL -> {
                    val left = minOf(start.x, end.x)
                    val top = minOf(start.y, end.y)
                    val width = kotlin.math.abs(end.x - start.x)
                    val height = kotlin.math.abs(end.y - start.y)
                    drawOval(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = annot.strokeWidth)
                    )
                }
                ToolType.LINE -> {
                    drawLine(
                        color = color,
                        start = start,
                        end = end,
                        strokeWidth = annot.strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
                ToolType.ARROW -> {
                    drawArrow(start, end, color, annot.strokeWidth)
                }
                else -> Unit
            }
        }
        is Annotation.Signature -> {
            annot.strokes.forEach { stroke ->
                if (stroke.size > 1) {
                    val path = buildSmoothedPath(stroke)
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = annot.strokeWidth,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
            }
        }
        is Annotation.TextMarkup -> {
            annot.rects.forEach { rect ->
                val composeRect = rect.toComposeRect(pageWidth, pageHeight)
                when (annot.markupType) {
                    ToolType.UNDERLINE -> {
                        drawLine(
                            color = color,
                            start = Offset(composeRect.left, composeRect.bottom),
                            end = Offset(composeRect.right, composeRect.bottom),
                            strokeWidth = annot.strokeWidth
                        )
                    }
                    ToolType.STRIKEOUT -> {
                        val midY = (composeRect.top + composeRect.bottom) / 2f
                        drawLine(
                            color = color,
                            start = Offset(composeRect.left, midY),
                            end = Offset(composeRect.right, midY),
                            strokeWidth = annot.strokeWidth
                        )
                    }
                    else -> Unit
                }
            }
        }
        else -> Unit
    }
}

private fun DrawScope.drawActiveStroke(
    activeTool: ToolType,
    points: List<StrokePoint>,
    color: Color,
    strokeWidth: Float,
    alpha: Float
) {
    if (points.size < 2) return
    val strokeColor = color.copy(alpha = alpha)

    when (activeTool) {
        ToolType.STYLUS_PEN -> {
            val path = buildSmoothedPath(points)
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        ToolType.HIGHLIGHTER -> {
            val path = buildSmoothedPath(points)
            drawPath(
                path = path,
                color = strokeColor,
                style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Round),
                blendMode = BlendMode.Multiply
            )
        }
        ToolType.RECTANGLE -> {
            val start = points.first()
            val end = points.last()
            val left = minOf(start.x, end.x)
            val top = minOf(start.y, end.y)
            val width = kotlin.math.abs(end.x - start.x)
            val height = kotlin.math.abs(end.y - start.y)
            drawRect(
                color = strokeColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = strokeWidth)
            )
        }
        ToolType.OVAL -> {
            val start = points.first()
            val end = points.last()
            val left = minOf(start.x, end.x)
            val top = minOf(start.y, end.y)
            val width = kotlin.math.abs(end.x - start.x)
            val height = kotlin.math.abs(end.y - start.y)
            drawOval(
                color = strokeColor,
                topLeft = Offset(left, top),
                size = Size(width, height),
                style = Stroke(width = strokeWidth)
            )
        }
        ToolType.LINE -> {
            drawLine(
                color = strokeColor,
                start = Offset(points.first().x, points.first().y),
                end = Offset(points.last().x, points.last().y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
        ToolType.ARROW -> {
            drawArrow(
                Offset(points.first().x, points.first().y),
                Offset(points.last().x, points.last().y),
                strokeColor,
                strokeWidth
            )
        }
        else -> Unit
    }
}

private fun DrawScope.drawArrow(start: Offset, end: Offset, color: Color, strokeWidth: Float) {
    drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val arrowHeadLength = 24.dp.toPx()
    val arrowAngle = Math.toRadians(30.0)

    val x1 = end.x - (arrowHeadLength * cos(angle - arrowAngle)).toFloat()
    val y1 = end.y - (arrowHeadLength * sin(angle - arrowAngle)).toFloat()
    val x2 = end.x - (arrowHeadLength * cos(angle + arrowAngle)).toFloat()
    val y2 = end.y - (arrowHeadLength * sin(angle + arrowAngle)).toFloat()

    val path = Path().apply {
        moveTo(end.x, end.y)
        lineTo(x1, y1)
        moveTo(end.x, end.y)
        lineTo(x2, y2)
    }
    drawPath(path = path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
}

/**
 * Creates smooth quadratic Bézier spline curves between stroke points.
 */
private fun buildSmoothedPath(points: List<StrokePoint>): Path {
    val path = Path()
    if (points.isEmpty()) return path

    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path

    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val midX = (prev.x + curr.x) / 2f
        val midY = (prev.y + curr.y) / 2f
        path.quadraticTo(prev.x, prev.y, midX, midY)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

private fun createAnnotationFromStroke(
    pageIndex: Int,
    activeTool: ToolType,
    points: List<StrokePoint>,
    color: Color,
    strokeWidth: Float,
    alpha: Float
): Annotation? {
    if (points.isEmpty()) return null

    return when (activeTool) {
        ToolType.STYLUS_PEN -> {
            Annotation.Ink(
                pageIndex = pageIndex,
                colorArgb = color.toArgb(),
                alpha = alpha,
                strokeWidth = strokeWidth,
                points = points
            )
        }
        ToolType.HIGHLIGHTER -> {
            Annotation.Highlight(
                pageIndex = pageIndex,
                colorArgb = color.toArgb(),
                alpha = 0.45f,
                strokeWidth = 24.0f,
                freehandPoints = points
            )
        }
        ToolType.RECTANGLE, ToolType.OVAL, ToolType.LINE, ToolType.ARROW -> {
            Annotation.Shape(
                pageIndex = pageIndex,
                colorArgb = color.toArgb(),
                alpha = alpha,
                strokeWidth = strokeWidth,
                shapeType = activeTool,
                startPoint = points.first(),
                endPoint = points.last()
            )
        }
        else -> null
    }
}

