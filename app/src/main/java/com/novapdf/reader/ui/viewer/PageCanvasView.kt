package com.novapdf.reader.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = (configuration.screenWidthDp * configuration.fontScale * 2.5f).toInt().coerceIn(720, 1400)
    val aspectRatio = dimension?.aspectRatio ?: (1f / 1.414f)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Autonomous per-page bitmap rendering with LRU cache check
    val pageBitmap by produceState<Bitmap?>(initialValue = pdfEngine.getCachedPage(pageIndex, screenWidthPx), key1 = pageIndex) {
        if (value == null) {
            value = pdfEngine.renderPage(pageIndex, targetWidth = screenWidthPx)
        }
    }

    // PDFBox per-page extracted text content
    val textContent by produceState<PageTextContent?>(initialValue = textExtractor.getCachedPageText(pageIndex), key1 = pageIndex) {
        if (value == null) {
            value = textExtractor.extractPageText(documentUri, pageIndex, 800, 1100)
        }
    }

    // Text Selection state
    val selectedWords = remember { mutableStateListOf<PdfWord>() }
    var selectionMenuOffset by remember { mutableStateOf<Offset?>(null) }
    var dragStartOffset by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentOffset by remember { mutableStateOf<Offset?>(null) }

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

    fun updateSelectionFromDrag(start: Offset, end: Offset) {
        val width = canvasSize.width.toFloat().coerceAtLeast(1f)
        val height = canvasSize.height.toFloat().coerceAtLeast(1f)

        val minNormX = (minOf(start.x, end.x) / width).coerceIn(0f, 1f)
        val maxNormX = (maxOf(start.x, end.x) / width).coerceIn(0f, 1f)
        val minNormY = (minOf(start.y, end.y) / height).coerceIn(0f, 1f)
        val maxNormY = (maxOf(start.y, end.y) / height).coerceIn(0f, 1f)

        val words = textContent?.lines?.flatMap { it.words } ?: emptyList()
        val matched = words.filter { word ->
            word.bounds.right >= minNormX && word.bounds.left <= maxNormX &&
            word.bounds.bottom >= minNormY && word.bounds.top <= maxNormY
        }

        selectedWords.clear()
        selectedWords.addAll(matched)

        if (matched.isNotEmpty()) {
            val menuX = (matched.first().bounds.left * width).coerceIn(20f, width - 260f)
            val menuY = ((matched.first().bounds.top * height) - 60f).coerceAtLeast(10f)
            selectionMenuOffset = Offset(menuX, menuY)
        } else {
            selectionMenuOffset = null
        }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(activeTool, textContent) {
                    if (activeTool == ToolType.TEXT_SELECT) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStartOffset = offset
                                dragCurrentOffset = offset
                                updateSelectionFromDrag(offset, offset)
                            },
                            onDrag = { change, _ ->
                                dragCurrentOffset = change.position
                                dragStartOffset?.let { start ->
                                    updateSelectionFromDrag(start, change.position)
                                }
                            },
                            onDragEnd = {
                                dragStartOffset = null
                                dragCurrentOffset = null
                            },
                            onDragCancel = {
                                dragStartOffset = null
                                dragCurrentOffset = null
                            }
                        )
                    }
                }
        ) {
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

            // 3. Text Selection Highlights (Translucent Blue with handles)
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
                            color = Color(0x552196F3),
                            topLeft = Offset(left, top),
                            size = Size(width, height),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }

            // 4. Vector Annotation Drawing Layer
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

            // 5. Floating Text Selection Quick Action Menu
            if (selectedWords.isNotEmpty() && selectionMenuOffset != null) {
                val offset = selectionMenuOffset!!
                Box(
                    modifier = Modifier
                        .offset { IntOffset(offset.x.toInt(), offset.y.toInt()) }
                ) {
                    TextSelectionMenu(
                        onCopyClicked = {
                            val selectedText = selectedWords.joinToString(" ") { it.text }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("PDF Text", selectedText))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            selectedWords.clear()
                            selectionMenuOffset = null
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
                            selectedWords.clear()
                            selectionMenuOffset = null
                        },
                        onUnderlineClicked = {
                            val markup = Annotation.TextMarkup(
                                pageIndex = pageIndex,
                                colorArgb = Color(0xFFD32F2F).toArgb(),
                                strokeWidth = 2f,
                                markupType = ToolType.UNDERLINE,
                                rects = selectedWords.map { it.bounds }
                            )
                            onAnnotationAdded(markup)
                            selectedWords.clear()
                            selectionMenuOffset = null
                        },
                        onSpeakClicked = {
                            val firstWord = selectedWords.firstOrNull()
                            if (firstWord != null) {
                                val sentence = textContent?.sentences?.firstOrNull { s ->
                                    s.charOffsetStart <= firstWord.charStart && s.charOffsetEnd >= firstWord.charEnd
                                }
                                val sIndex = sentence?.sentenceIndex ?: 0
                                onSpeakFromSentenceRequested(sIndex)
                            }
                            selectedWords.clear()
                            selectionMenuOffset = null
                        },
                        onDismissClicked = {
                            selectedWords.clear()
                            selectionMenuOffset = null
                        }
                    )
                }
            }

            // 6. Subtle Page Number Pill
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
