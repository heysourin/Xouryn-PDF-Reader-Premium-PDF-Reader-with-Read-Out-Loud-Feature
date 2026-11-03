package com.novapdf.reader.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.UUID

enum class ToolType {
    PAN,
    TEXT_SELECT, // Xodo-style text selection, copy, and markup
    STYLUS_PEN,
    HIGHLIGHTER,
    UNDERLINE,
    STRIKEOUT,
    SQUIGGLY,
    RECTANGLE,
    OVAL,
    ARROW,
    LINE,
    FREE_TEXT,
    SIGNATURE,
    STICKY_NOTE,
    ERASER,
    TTS_READER // ReadEra interactive tap-to-read mode
}

data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class Annotation(
    open val id: String = UUID.randomUUID().toString(),
    open val pageIndex: Int,
    open val colorArgb: Int,
    open val alpha: Float = 1.0f,
    open val strokeWidth: Float = 3.0f,
    open val createdAt: Long = System.currentTimeMillis()
) {
    /** Freehand ink annotation (Stylus / Pen) */
    data class Ink(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFF0066FF).toArgb(),
        override val alpha: Float = 1.0f,
        override val strokeWidth: Float = 4.0f,
        val points: List<StrokePoint> = emptyList(),
        val isPressureSensitive: Boolean = true
    ) : Annotation(id, pageIndex, colorArgb, alpha, strokeWidth)

    /** Highlighter annotation with Multiply blend mode */
    data class Highlight(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFFFFEB3B).toArgb(),
        override val alpha: Float = 0.45f,
        override val strokeWidth: Float = 24.0f,
        val rects: List<NormalizedRect> = emptyList(),
        val freehandPoints: List<StrokePoint> = emptyList()
    ) : Annotation(id, pageIndex, colorArgb, alpha, strokeWidth)

    /** Text Markup (Underline, Strikeout, Squiggly) */
    data class TextMarkup(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFFD32F2F).toArgb(),
        override val alpha: Float = 1.0f,
        override val strokeWidth: Float = 2.0f,
        val markupType: ToolType, // UNDERLINE, STRIKEOUT, SQUIGGLY
        val rects: List<NormalizedRect> = emptyList()
    ) : Annotation(id, pageIndex, colorArgb, alpha, strokeWidth)

    /** Geometric shape annotation */
    data class Shape(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFFE53935).toArgb(),
        override val alpha: Float = 1.0f,
        override val strokeWidth: Float = 3.0f,
        val shapeType: ToolType, // RECTANGLE, OVAL, ARROW, LINE
        val startPoint: StrokePoint,
        val endPoint: StrokePoint,
        val isFilled: Boolean = false,
        val fillColorArgb: Int = Color.Transparent.toArgb()
    ) : Annotation(id, pageIndex, colorArgb, alpha, strokeWidth)

    /** Text Box (FreeText) */
    data class FreeText(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFF212121).toArgb(),
        override val alpha: Float = 1.0f,
        override val strokeWidth: Float = 1.0f,
        val text: String,
        val bounds: NormalizedRect,
        val fontSize: Float = 16.0f,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val backgroundColorArgb: Int = Color(0xFFFFFFFF).toArgb()
    ) : Annotation(id, pageIndex, colorArgb, alpha, strokeWidth)

    /** Handwritten signature vector stamp */
    data class Signature(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFF1A237E).toArgb(),
        override val alpha: Float = 1.0f,
        override val strokeWidth: Float = 3.0f,
        val strokes: List<List<StrokePoint>>,
        val bounds: NormalizedRect
    ) : Annotation(id, pageIndex, colorArgb, alpha, strokeWidth)

    /** Sticky comment note */
    data class StickyNote(
        override val id: String = UUID.randomUUID().toString(),
        override val pageIndex: Int,
        override val colorArgb: Int = Color(0xFFFFB300).toArgb(),
        val position: StrokePoint,
        val comment: String = "",
        val author: String = "User"
    ) : Annotation(id, pageIndex, colorArgb, 1.0f, 1.0f)
}

