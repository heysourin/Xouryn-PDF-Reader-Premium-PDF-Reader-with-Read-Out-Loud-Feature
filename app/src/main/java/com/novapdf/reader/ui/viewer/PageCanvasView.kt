package com.novapdf.reader.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novapdf.reader.engine.PdfEngine
import com.novapdf.reader.engine.PdfTextExtractor
import com.novapdf.reader.model.Annotation
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.PageDimension
import com.novapdf.reader.model.PageTextContent
import com.novapdf.reader.model.PdfWord
import com.novapdf.reader.model.SpeechPosition
import com.novapdf.reader.model.ToolType
import com.novapdf.reader.ui.annotations.AnnotationOverlayCanvas
import kotlinx.coroutines.launch

@Composable
fun PageCanvasView(
    pageIndex: Int,
    dimension: PageDimension?,
    pdfEngine: PdfEngine,
    textExtractor: PdfTextExtractor,
    documentUri: Uri,
    annotations: List<Annotation>,
    activeTool: ToolType,
    currentColor: Color,
    currentStrokeWidth: Float,
    currentAlpha: Float,
    speechPosition: SpeechPosition?,
    onAnnotationAdded: (Annotation) -> Unit,
    onEraseRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    onTapToReadRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    onSpeakFromSentenceRequested: (sentenceIndex: Int) -> Unit,
    onPageTapped: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    // Optimized memory budget: render at crisp display width without wasting RAM
    val screenWidthPx = (configuration.screenWidthDp * 1.8f).toInt().coerceIn(600, 900)
    val aspectRatio = dimension?.aspectRatio ?: (1f / 1.414f)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // 1. Autonomous per-page bitmap rendering with bounded LRU cache
    val pageBitmap by produceState<Bitmap?>(initialValue = pdfEngine.getCachedPage(pageIndex, screenWidthPx), key1 = pageIndex) {
        if (value == null) {
            value = pdfEngine.renderPage(pageIndex, targetWidth = screenWidthPx)
        }
    }

    // 2. On-Demand Text Content: NEVER eagerly extracts on scroll to keep CPU cool and RAM light!
    var textContent by remember(pageIndex) {
        mutableStateOf(textExtractor.getCachedPageText(pageIndex))
    }

    // Load text only when TTS is speaking this page or pointing at it
    LaunchedEffect(speechPosition?.pageIndex) {
        if (speechPosition?.pageIndex == pageIndex && textContent == null) {
            textContent = textExtractor.extractPageText(documentUri, pageIndex, 800, 1100)
        }
    }

    // Load text if user explicitly switches to Text Select mode
    LaunchedEffect(activeTool) {
        if (activeTool == ToolType.TEXT_SELECT && textContent == null) {
            textContent = textExtractor.extractPageText(documentUri, pageIndex, 800, 1100)
        }
    }

    // 3. Selection Range State (Word Index based)
    var selectedStartIndex by remember { mutableIntStateOf(-1) }
    var selectedEndIndex by remember { mutableIntStateOf(-1) }

    val allWords = remember(textContent) {
        textContent?.lines?.flatMap { it.words } ?: emptyList()
    }

    val selectedWords = remember(selectedStartIndex, selectedEndIndex, allWords) {
        if (selectedStartIndex != -1 && selectedEndIndex != -1 && allWords.isNotEmpty()) {
            val minIdx = minOf(selectedStartIndex, selectedEndIndex).coerceIn(0, allWords.lastIndex)
            val maxIdx = maxOf(selectedStartIndex, selectedEndIndex).coerceIn(0, allWords.lastIndex)
            allWords.subList(minIdx, maxIdx + 1)
        } else {
            emptyList()
        }
    }

    fun clearSelection() {
        selectedStartIndex = -1
        selectedEndIndex = -1
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .shadow(3.dp, RoundedCornerShape(4.dp))
            .onSizeChanged { canvasSize = it },
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // 1. PDF Rendered Bitmap
            val bmp = pageBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
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

            // 2. ReadEra-Style Live TTS Speech Glowing Overlay
            // Isolated in dedicated composable so it NEVER triggers 120Hz recomposition loops on idle pages!
            if (speechPosition != null && speechPosition.pageIndex == pageIndex) {
                TtsSpeechHighlightLayer(speechPosition = speechPosition)
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
                    val normX = if (canvasSize.width > 0) x / canvasSize.width else 0.5f
                    val normY = if (canvasSize.height > 0) y / canvasSize.height else 0.5f
                    onEraseRequested(normX, normY)
                },
                onTapToReadRequested = { x, y ->
                    val normX = if (canvasSize.width > 0) x / canvasSize.width else 0.5f
                    val normY = if (canvasSize.height > 0) y / canvasSize.height else 0.5f
                    onTapToReadRequested(normX, normY)
                },
                modifier = Modifier.fillMaxSize()
            )

            // 4. Text Selection Interaction & Gesture Layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activeTool, allWords, canvasSize) {
                        if (activeTool == ToolType.TEXT_SELECT) {
                            // Direct drag-to-select in TEXT_SELECT mode
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val normX = (offset.x / canvasSize.width.toFloat()).coerceIn(0f, 1f)
                                    val normY = (offset.y / canvasSize.height.toFloat()).coerceIn(0f, 1f)
                                    val idx = findWordIndexAt(allWords, normX, normY)
                                    if (idx != -1) {
                                        selectedStartIndex = idx
                                        selectedEndIndex = idx
                                    }
                                },
                                onDrag = { change, _ ->
                                    val normX = (change.position.x / canvasSize.width.toFloat()).coerceIn(0f, 1f)
                                    val normY = (change.position.y / canvasSize.height.toFloat()).coerceIn(0f, 1f)
                                    val idx = findWordIndexAt(allWords, normX, normY)
                                    if (idx != -1) {
                                        selectedEndIndex = idx
                                    }
                                }
                            )
                        } else if (activeTool == ToolType.PAN || activeTool == ToolType.TTS_READER) {
                            // Standard Reader & Read-Out-Loud Mode: Long-press to select text, tap to toggle fullscreen!
                            detectTapGestures(
                                onLongPress = { touchOffset ->
                                    scope.launch {
                                        var words = allWords
                                        if (words.isEmpty()) {
                                            val extracted = textExtractor.extractPageText(
                                                documentUri, pageIndex, canvasSize.width, canvasSize.height
                                            )
                                            textContent = extracted
                                            words = extracted.lines.flatMap { it.words }
                                        }

                                        if (words.isNotEmpty()) {
                                            val normX = (touchOffset.x / canvasSize.width.toFloat()).coerceIn(0f, 1f)
                                            val normY = (touchOffset.y / canvasSize.height.toFloat()).coerceIn(0f, 1f)
                                            val idx = findWordIndexAt(words, normX, normY)
                                            if (idx != -1) {
                                                selectedStartIndex = idx
                                                selectedEndIndex = idx
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        } else {
                                            Toast.makeText(context, "No text found on this page", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onTap = {
                                    if (selectedStartIndex != -1) {
                                        clearSelection()
                                    } else {
                                        onPageTapped()
                                    }
                                }
                            )
                        }
                    }
            ) {
                // Draw Selection Highlight Rectangles
                if (selectedWords.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pageWidth = size.width
                        val pageHeight = size.height

                        selectedWords.forEach { word ->
                            val left = word.bounds.left * pageWidth
                            val top = word.bounds.top * pageHeight
                            val width = word.bounds.width * pageWidth
                            val height = word.bounds.height * pageHeight

                            drawRoundRect(
                                color = Color(0x502196F3),
                                topLeft = Offset(left, top),
                                size = Size(width, height),
                                cornerRadius = CornerRadius(2.dp.toPx())
                            )
                        }
                    }

                    // Start Selection Handle (Teardrop pin)
                    val startWord = selectedWords.first()
                    val startHandleX = (startWord.bounds.left * canvasSize.width).coerceIn(0f, canvasSize.width.toFloat())
                    val startHandleY = (startWord.bounds.bottom * canvasSize.height).coerceIn(0f, canvasSize.height.toFloat())
                    val startLineHeight = (startWord.bounds.height * canvasSize.height).coerceAtLeast(16f)

                    SelectionHandle(
                        isStart = true,
                        anchorOffset = Offset(startHandleX, startHandleY),
                        lineHeight = startLineHeight,
                        onHandleDragged = { dragDelta ->
                            val newX = (startHandleX + dragDelta.x) / canvasSize.width.toFloat()
                            val newY = (startHandleY + dragDelta.y) / canvasSize.height.toFloat()
                            val newIdx = findWordIndexAt(allWords, newX, newY)
                            if (newIdx != -1) {
                                selectedStartIndex = newIdx
                            }
                        }
                    )

                    // End Selection Handle (Teardrop pin)
                    val endWord = selectedWords.last()
                    val endHandleX = (endWord.bounds.right * canvasSize.width).coerceIn(0f, canvasSize.width.toFloat())
                    val endHandleY = (endWord.bounds.bottom * canvasSize.height).coerceIn(0f, canvasSize.height.toFloat())
                    val endLineHeight = (endWord.bounds.height * canvasSize.height).coerceAtLeast(16f)

                    SelectionHandle(
                        isStart = false,
                        anchorOffset = Offset(endHandleX, endHandleY),
                        lineHeight = endLineHeight,
                        onHandleDragged = { dragDelta ->
                            val newX = (endHandleX + dragDelta.x) / canvasSize.width.toFloat()
                            val newY = (endHandleY + dragDelta.y) / canvasSize.height.toFloat()
                            val newIdx = findWordIndexAt(allWords, newX, newY)
                            if (newIdx != -1) {
                                selectedEndIndex = newIdx
                            }
                        }
                    )

                    // Floating Action Menu: Cleanly calculated with density & clearance so it NEVER overlaps text or handles!
                    val firstWord = selectedWords.first()
                    val menuEstimatedWidthPx = with(density) { 340.dp.toPx() }
                    val menuHeightPx = with(density) { 52.dp.toPx() }
                    val menuMarginPx = with(density) { 16.dp.toPx() }

                    // Horizontal position: Center above the selected words range
                    val selLeftPx = firstWord.bounds.left * canvasSize.width
                    val selRightPx = endWord.bounds.right * canvasSize.width
                    val selCenterXPx = (selLeftPx + selRightPx) / 2f
                    val menuX = (selCenterXPx - menuEstimatedWidthPx / 2f).coerceIn(
                        with(density) { 8.dp.toPx() },
                        (canvasSize.width - menuEstimatedWidthPx - with(density) { 8.dp.toPx() }).coerceAtLeast(with(density) { 8.dp.toPx() })
                    )

                    // Vertical position: Ensure it is placed comfortably ABOVE the top of the first word
                    val startWordTopPx = firstWord.bounds.top * canvasSize.height
                    val endWordBottomPx = endWord.bounds.bottom * canvasSize.height

                    val spaceAbove = startWordTopPx - menuHeightPx - menuMarginPx
                    val menuY = if (spaceAbove >= with(density) { 16.dp.toPx() }) {
                        spaceAbove
                    } else {
                        endWordBottomPx + with(density) { 34.dp.toPx() }
                    }

                    Box(
                        modifier = Modifier.offset { IntOffset(menuX.toInt(), menuY.toInt()) }
                    ) {
                        TextSelectionMenu(
                            onCopyClicked = {
                                val text = selectedWords.joinToString(" ") { it.text }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", text))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                clearSelection()
                            },
                            onHighlightClicked = {
                                val highlight = Annotation.Highlight(
                                    pageIndex = pageIndex,
                                    colorArgb = Color(0xFFFFEB3B).toArgb(),
                                    alpha = 0.45f,
                                    strokeWidth = 20f,
                                    rects = selectedWords.map { it.bounds }
                                )
                                onAnnotationAdded(highlight)
                                clearSelection()
                            },
                            onUnderlineClicked = {
                                val markup = Annotation.TextMarkup(
                                    pageIndex = pageIndex,
                                    colorArgb = Color(0xFF1976D2).toArgb(),
                                    strokeWidth = 2f,
                                    markupType = ToolType.UNDERLINE,
                                    rects = selectedWords.map { it.bounds }
                                )
                                onAnnotationAdded(markup)
                                clearSelection()
                            },
                            onStrikeoutClicked = {
                                val markup = Annotation.TextMarkup(
                                    pageIndex = pageIndex,
                                    colorArgb = Color(0xFFD32F2F).toArgb(),
                                    strokeWidth = 2f,
                                    markupType = ToolType.STRIKEOUT,
                                    rects = selectedWords.map { it.bounds }
                                )
                                onAnnotationAdded(markup)
                                clearSelection()
                            },
                            onSpeakClicked = {
                                val first = selectedWords.firstOrNull()
                                if (first != null) {
                                    val sentence = textContent?.sentences?.firstOrNull { s ->
                                        s.charOffsetStart <= first.charStart && s.charOffsetEnd >= first.charEnd
                                    }
                                    val sIndex = sentence?.sentenceIndex ?: 0
                                    onSpeakFromSentenceRequested(sIndex)
                                }
                                clearSelection()
                            },
                            onSelectAllClicked = {
                                if (allWords.isNotEmpty()) {
                                    selectedStartIndex = 0
                                    selectedEndIndex = allWords.lastIndex
                                }
                            },
                            onShareClicked = {
                                val text = selectedWords.joinToString(" ") { it.text }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share PDF Text"))
                                clearSelection()
                            },
                            onDismissClicked = {
                                clearSelection()
                            }
                        )
                    }
                }
            }

            // 5. Page Number Pill
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

/**
 * Isolated TTS Speech Highlight layer.
 * Only runs the animation when actively speaking on this specific page, saving massive CPU and battery!
 */
@Composable
private fun TtsSpeechHighlightLayer(
    speechPosition: SpeechPosition
) {
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

            drawRoundRect(
                color = Color(0xFFFFEB3B).copy(alpha = pulseAlpha),
                topLeft = Offset(left - 2.dp.toPx(), top - 2.dp.toPx()),
                size = Size(width + 4.dp.toPx(), height + 4.dp.toPx()),
                cornerRadius = CornerRadius(4.dp.toPx())
            )

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

/**
 * Draggable selection teardrop pin handle
 */
@Composable
private fun SelectionHandle(
    isStart: Boolean,
    anchorOffset: Offset,
    lineHeight: Float,
    onHandleDragged: (dragDelta: Offset) -> Unit
) {
    var accumulatedDelta by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset {
                val x = if (isStart) anchorOffset.x - 18.dp.toPx() else anchorOffset.x - 2.dp.toPx()
                val y = anchorOffset.y - lineHeight
                IntOffset(x.toInt(), y.toInt())
            }
            .size(36.dp, (lineHeight.dp + 24.dp))
            .pointerInput(isStart, anchorOffset) {
                detectDragGestures(
                    onDragStart = { accumulatedDelta = Offset.Zero },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDelta += dragAmount
                        onHandleDragged(accumulatedDelta)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val handleColor = Color(0xFF1976D2)
            val lineX = if (isStart) 18.dp.toPx() else 2.dp.toPx()

            // Vertical cursor bar
            drawLine(
                color = handleColor,
                start = Offset(lineX, 0f),
                end = Offset(lineX, lineHeight),
                strokeWidth = 2.5.dp.toPx()
            )

            // Teardrop pin circle below the line
            val circleRadius = 7.dp.toPx()
            val circleCenterX = if (isStart) lineX - circleRadius + 2.dp.toPx() else lineX + circleRadius - 2.dp.toPx()
            val circleCenterY = lineHeight + circleRadius

            drawCircle(
                color = handleColor,
                radius = circleRadius,
                center = Offset(circleCenterX, circleCenterY)
            )
        }
    }
}

/**
 * Robust word hit-testing supporting exact bounds and proximity line matching
 */
private fun findWordIndexAt(words: List<PdfWord>, normX: Float, normY: Float): Int {
    if (words.isEmpty()) return -1

    // 1. Direct hit on bounding box (with slight padding for touch precision)
    val direct = words.indexOfFirst { word ->
        val padX = 0.015f
        val padY = 0.012f
        normX >= (word.bounds.left - padX) && normX <= (word.bounds.right + padX) &&
        normY >= (word.bounds.top - padY) && normY <= (word.bounds.bottom + padY)
    }
    if (direct != -1) return direct

    // 2. Line proximity match: closest word on the same vertical line
    val lineCandidates = words.mapIndexed { idx, word -> idx to word }.filter { (_, word) ->
        val midY = (word.bounds.top + word.bounds.bottom) / 2f
        kotlin.math.abs(midY - normY) < 0.025f
    }
    if (lineCandidates.isNotEmpty()) {
        return lineCandidates.minByOrNull { (_, word) ->
            val midX = (word.bounds.left + word.bounds.right) / 2f
            kotlin.math.abs(midX - normX)
        }?.first ?: -1
    }

    // 3. Overall closest word
    return words.indices.minByOrNull { idx ->
        val word = words[idx]
        val midX = (word.bounds.left + word.bounds.right) / 2f
        val midY = (word.bounds.top + word.bounds.bottom) / 2f
        val dx = midX - normX
        val dy = (midY - normY) * 2f
        dx * dx + dy * dy
    } ?: -1
}
