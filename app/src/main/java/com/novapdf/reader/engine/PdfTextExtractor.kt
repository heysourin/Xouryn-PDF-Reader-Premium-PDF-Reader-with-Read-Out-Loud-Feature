package com.novapdf.reader.engine

import android.content.Context
import android.net.Uri
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.PageTextContent
import com.novapdf.reader.model.PdfSentence
import com.novapdf.reader.model.PdfTextLine
import com.novapdf.reader.model.PdfWord
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.BreakIterator
import java.util.Locale

class PdfTextExtractor(private val context: Context) {

    private val pageTextCache = HashMap<Int, PageTextContent>()

    suspend fun extractPageText(
        uri: Uri,
        pageIndex: Int,
        pageWidth: Int,
        pageHeight: Int
    ): PageTextContent = withContext(Dispatchers.IO) {
        pageTextCache[pageIndex]?.let { return@withContext it }

        var pdDocument: PDDocument? = null
        try {
            val stream: InputStream? = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                pdDocument = PDDocument.load(stream)
                val totalPages = pdDocument.numberOfPages

                if (pageIndex in 0 until totalPages) {
                    val page = pdDocument.getPage(pageIndex)
                    val mediaBox = page.cropBox ?: page.mediaBox
                    val actualWidth = if (mediaBox != null && mediaBox.width > 0) mediaBox.width else pageWidth.toFloat().coerceAtLeast(1f)
                    val actualHeight = if (mediaBox != null && mediaBox.height > 0) mediaBox.height else pageHeight.toFloat().coerceAtLeast(1f)

                    val wordsOnPage = ArrayList<PdfWord>()
                    val linesOnPage = ArrayList<PdfTextLine>()
                    val pageTextSb = StringBuilder()

                    val stripper = object : PDFTextStripper() {
                        init {
                            startPage = pageIndex + 1
                            endPage = pageIndex + 1
                            sortByPosition = true
                        }

                        override fun writeString(string: String, textPositions: MutableList<TextPosition>) {
                            super.writeString(string, textPositions)
                            if (textPositions.isEmpty()) return

                            val currentWordSb = StringBuilder()
                            var wordStartChar = pageTextSb.length
                            var minX = Float.MAX_VALUE
                            var minY = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxY = Float.MIN_VALUE

                            val lineWords = ArrayList<PdfWord>()
                            var lineMinX = Float.MAX_VALUE
                            var lineMinY = Float.MAX_VALUE
                            var lineMaxX = Float.MIN_VALUE
                            var lineMaxY = Float.MIN_VALUE

                            for (pos in textPositions) {
                                val charStr = pos.unicode ?: continue
                                val x = (pos.xDirAdj / actualWidth).coerceIn(0f, 1f)
                                val topY = ((pos.yDirAdj - pos.heightDir) / actualHeight).coerceIn(0f, 1f)
                                val w = (pos.widthDirAdj / actualWidth).coerceIn(0f, 1f)
                                val h = (pos.heightDir / actualHeight).coerceIn(0f, 1f)

                                lineMinX = minOf(lineMinX, x)
                                lineMinY = minOf(lineMinY, topY)
                                lineMaxX = maxOf(lineMaxX, x + w)
                                lineMaxY = maxOf(lineMaxY, topY + h)

                                if (charStr == " " || charStr == "\t") {
                                    if (currentWordSb.isNotEmpty()) {
                                        val wordText = currentWordSb.toString()
                                        val word = PdfWord(
                                            text = wordText,
                                            bounds = NormalizedRect(
                                                left = minX.coerceIn(0f, 1f),
                                                top = minY.coerceIn(0f, 1f),
                                                right = maxX.coerceIn(0f, 1f),
                                                bottom = maxY.coerceIn(0f, 1f)
                                            ),
                                            charStart = wordStartChar,
                                            charEnd = pageTextSb.length
                                        )
                                        wordsOnPage.add(word)
                                        lineWords.add(word)
                                        currentWordSb.clear()
                                        minX = Float.MAX_VALUE
                                        minY = Float.MAX_VALUE
                                        maxX = Float.MIN_VALUE
                                        maxY = Float.MIN_VALUE
                                    }
                                    pageTextSb.append(charStr)
                                    wordStartChar = pageTextSb.length
                                } else {
                                    if (currentWordSb.isEmpty()) {
                                        wordStartChar = pageTextSb.length
                                    }
                                    currentWordSb.append(charStr)
                                    minX = minOf(minX, x)
                                    minY = minOf(minY, topY)
                                    maxX = maxOf(maxX, x + w)
                                    maxY = maxOf(maxY, topY + h)
                                    pageTextSb.append(charStr)
                                }
                            }

                            if (currentWordSb.isNotEmpty()) {
                                val wordText = currentWordSb.toString()
                                val word = PdfWord(
                                    text = wordText,
                                    bounds = NormalizedRect(
                                        left = minX.coerceIn(0f, 1f),
                                        top = minY.coerceIn(0f, 1f),
                                        right = maxX.coerceIn(0f, 1f),
                                        bottom = maxY.coerceIn(0f, 1f)
                                    ),
                                    charStart = wordStartChar,
                                    charEnd = pageTextSb.length
                                )
                                wordsOnPage.add(word)
                                lineWords.add(word)
                            }

                            if (lineWords.isNotEmpty()) {
                                linesOnPage.add(
                                    PdfTextLine(
                                        lineIndex = linesOnPage.size,
                                        text = string.trim(),
                                        bounds = NormalizedRect(
                                            left = lineMinX.coerceIn(0f, 1f),
                                            top = lineMinY.coerceIn(0f, 1f),
                                            right = lineMaxX.coerceIn(0f, 1f),
                                            bottom = lineMaxY.coerceIn(0f, 1f)
                                        ),
                                        words = lineWords
                                    )
                                )
                            }
                            pageTextSb.append("\n")
                        }
                    }

                    stripper.getText(pdDocument)

                    val pageFullText = pageTextSb.toString().trim()
                    if (pageFullText.isNotBlank()) {
                        val sentences = buildSentencesFromWords(pageFullText, wordsOnPage, linesOnPage)
                        val content = PageTextContent(
                            pageIndex = pageIndex,
                            fullText = pageFullText,
                            lines = linesOnPage,
                            sentences = sentences
                        )
                        pageTextCache[pageIndex] = content
                        return@withContext content
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                pdDocument?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val fallback = generateReadableFallbackText(pageIndex)
        pageTextCache[pageIndex] = fallback
        fallback
    }

    private fun buildSentencesFromWords(
        fullText: String,
        words: List<PdfWord>,
        lines: List<PdfTextLine>
    ): List<PdfSentence> {
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(fullText)

        val sentences = ArrayList<PdfSentence>()
        var start = iterator.first()
        var end = iterator.next()
        var sentenceIndex = 0

        while (end != BreakIterator.DONE) {
            val sentenceText = fullText.substring(start, end).trim()
            if (sentenceText.isNotBlank()) {
                // Find all words belonging to this sentence
                val sentenceWords = words.filter { it.charStart >= start && it.charEnd <= end }

                val lineBounds = if (sentenceWords.isNotEmpty()) {
                    // Group words by approximate vertical line position (within 1.5% Y difference)
                    sentenceWords.groupBy { (it.bounds.top * 100).toInt() }.values.map { lineWordGroup ->
                        val left = lineWordGroup.minOf { it.bounds.left }
                        val top = lineWordGroup.minOf { it.bounds.top }
                        val right = lineWordGroup.maxOf { it.bounds.right }
                        val bottom = lineWordGroup.maxOf { it.bounds.bottom }
                        NormalizedRect(left, top, right, bottom)
                    }
                } else {
                    emptyList()
                }

                val sentenceBounds = if (sentenceWords.isNotEmpty()) {
                    NormalizedRect(
                        left = sentenceWords.minOf { it.bounds.left },
                        top = sentenceWords.minOf { it.bounds.top },
                        right = sentenceWords.maxOf { it.bounds.right },
                        bottom = sentenceWords.maxOf { it.bounds.bottom }
                    )
                } else {
                    NormalizedRect(0.08f, 0.1f, 0.92f, 0.15f)
                }

                sentences.add(
                    PdfSentence(
                        sentenceIndex = sentenceIndex++,
                        text = sentenceText,
                        bounds = sentenceBounds,
                        lineBounds = lineBounds.ifEmpty { listOf(sentenceBounds) },
                        charOffsetStart = start,
                        charOffsetEnd = end
                    )
                )
            }
            start = end
            end = iterator.next()
        }

        return sentences
    }

    private fun generateReadableFallbackText(pageIndex: Int): PageTextContent {
        val text = "Page ${pageIndex + 1}. Scanned or image document without text layer."
        val bounds = NormalizedRect(0.08f, 0.08f, 0.92f, 0.12f)
        val sentence = PdfSentence(
            sentenceIndex = 0,
            text = text,
            bounds = bounds,
            lineBounds = listOf(bounds),
            charOffsetStart = 0,
            charOffsetEnd = text.length
        )
        return PageTextContent(
            pageIndex = pageIndex,
            fullText = text,
            lines = listOf(PdfTextLine(0, text, bounds)),
            sentences = listOf(sentence)
        )
    }

    fun getCachedPageText(pageIndex: Int): PageTextContent? {
        return pageTextCache[pageIndex]
    }

    fun clear() {
        pageTextCache.clear()
    }
}
