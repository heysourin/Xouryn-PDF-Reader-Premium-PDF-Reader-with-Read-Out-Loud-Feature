package com.novapdf.reader.model

import androidx.compose.ui.geometry.Rect

/**
 * Normalized bounding rectangle (coordinates between 0.0f and 1.0f relative to page dimensions).
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun toComposeRect(pageWidth: Float, pageHeight: Float): Rect {
        return Rect(
            left = left * pageWidth,
            top = top * pageHeight,
            right = right * pageWidth,
            bottom = bottom * pageHeight
        )
    }

    fun contains(x: Float, y: Float): Boolean {
        return x in left..right && y in top..bottom
    }
}

/**
 * Individual word bounding box on a page.
 */
data class PdfWord(
    val text: String,
    val bounds: NormalizedRect,
    val charStart: Int,
    val charEnd: Int
)

/**
 * A single line of text on a page.
 */
data class PdfTextLine(
    val lineIndex: Int,
    val text: String,
    val bounds: NormalizedRect,
    val words: List<PdfWord> = emptyList()
)

/**
 * A segmented sentence on a page, used by ReadEra TTS engine.
 */
data class PdfSentence(
    val sentenceIndex: Int,
    val text: String,
    val bounds: NormalizedRect,
    val lineBounds: List<NormalizedRect> = emptyList(),
    val charOffsetStart: Int = 0,
    val charOffsetEnd: Int = text.length
)

/**
 * Extracted text representation of an entire page.
 */
data class PageTextContent(
    val pageIndex: Int,
    val fullText: String,
    val lines: List<PdfTextLine> = emptyList(),
    val sentences: List<PdfSentence> = emptyList()
)

