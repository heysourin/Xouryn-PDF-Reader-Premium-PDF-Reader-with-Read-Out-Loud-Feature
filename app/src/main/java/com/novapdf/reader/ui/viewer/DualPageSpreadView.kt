package com.novapdf.reader.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.novapdf.reader.engine.PdfEngine
import com.novapdf.reader.model.Annotation
import com.novapdf.reader.model.PageDimension
import com.novapdf.reader.model.SpeechPosition
import com.novapdf.reader.model.ToolType

@Composable
fun DualPageSpreadView(
    leftPageIndex: Int,
    rightPageIndex: Int?,
    dimensions: List<PageDimension>,
    pdfEngine: PdfEngine,
    getAnnotationsForPage: (Int) -> List<Annotation>,
    activeTool: ToolType,
    currentColor: Color,
    currentStrokeWidth: Float,
    currentAlpha: Float,
    speechPosition: SpeechPosition?,
    onAnnotationAdded: (Annotation) -> Unit,
    onEraseRequested: (pageIndex: Int, x: Float, y: Float) -> Unit,
    onTapToReadRequested: (pageIndex: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Page
        val leftDim = dimensions.getOrNull(leftPageIndex)
        Box(modifier = Modifier.weight(1f)) {
            PageCanvasView(
                pageIndex = leftPageIndex,
                dimension = leftDim,
                pdfEngine = pdfEngine,
                annotations = getAnnotationsForPage(leftPageIndex),
                activeTool = activeTool,
                currentColor = currentColor,
                currentStrokeWidth = currentStrokeWidth,
                currentAlpha = currentAlpha,
                speechPosition = speechPosition,
                onAnnotationAdded = onAnnotationAdded,
                onEraseRequested = { x, y -> onEraseRequested(leftPageIndex, x, y) },
                onTapToReadRequested = { x, y -> onTapToReadRequested(leftPageIndex, x, y) }
            )
        }

        // Spine divider line between pages (Book mode)
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight(0.85f)
                .background(Color.Gray.copy(alpha = 0.2f))
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Right Page (if available)
        if (rightPageIndex != null) {
            val rightDim = dimensions.getOrNull(rightPageIndex)
            Box(modifier = Modifier.weight(1f)) {
                PageCanvasView(
                    pageIndex = rightPageIndex,
                    dimension = rightDim,
                    pdfEngine = pdfEngine,
                    annotations = getAnnotationsForPage(rightPageIndex),
                    activeTool = activeTool,
                    currentColor = currentColor,
                    currentStrokeWidth = currentStrokeWidth,
                    currentAlpha = currentAlpha,
                    speechPosition = speechPosition,
                    onAnnotationAdded = onAnnotationAdded,
                    onEraseRequested = { x, y -> onEraseRequested(rightPageIndex, x, y) },
                    onTapToReadRequested = { x, y -> onTapToReadRequested(rightPageIndex, x, y) }
                )
            }
        } else {
            // Empty placeholder for odd last page
            Box(modifier = Modifier.weight(1f))
        }
    }
}
