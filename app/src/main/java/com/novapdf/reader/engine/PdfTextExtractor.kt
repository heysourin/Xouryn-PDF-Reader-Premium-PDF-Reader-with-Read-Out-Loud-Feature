package com.novapdf.reader.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.novapdf.reader.model.NormalizedRect
import com.novapdf.reader.model.PageTextContent
import com.novapdf.reader.model.PdfSentence
import com.novapdf.reader.model.PdfTextLine
import com.novapdf.reader.model.PdfWord
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.BreakIterator
import java.util.Locale

class PdfTextExtractor(private val context: Context) {

    private val tag = "PdfTextExtractor"
    private val mutex = Mutex()

    private var currentUri: Uri? = null
    private var activeDocument: PDDocument? = null
    private var cachedPdfFile: File? = null

    // LRU Cache for extracted page text (holds up to 60 pages in memory)
    private val pageTextCache = LruCache<Int, PageTextContent>(60)

    suspend fun extractPageText(
        uri: Uri,
        pageIndex: Int,
        pageWidth: Int,
        pageHeight: Int
    ): PageTextContent = withContext(Dispatchers.IO) {
        // 1. Fast path: check LRU cache first
        pageTextCache.get(pageIndex)?.let { return@withContext it }

        mutex.withLock {
            // Re-check cache after acquiring lock
            pageTextCache.get(pageIndex)?.let { return@withContext it }

            ensureDocumentLoaded(uri)

            val doc = activeDocument
            if (doc != null && pageIndex in 0 until doc.numberOfPages) {
                try {
                    val page = doc.getPage(pageIndex)
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

                    stripper.getText(doc)

                    val pageFullText = pageTextSb.toString().trim()
                    if (pageFullText.isNotBlank()) {
                        val sentences = buildSentencesFromWords(pageFullText, wordsOnPage, linesOnPage)
                        val content = PageTextContent(
                            pageIndex = pageIndex,
                            fullText = pageFullText,
                            lines = linesOnPage,
                            sentences = sentences
                        )
                        pageTextCache.put(pageIndex, content)
                        return@withContext content
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error extracting text from page $pageIndex", e)
                }
            }

            val fallback = generateReadableFallbackText(pageIndex)
            pageTextCache.put(pageIndex, fallback)
            fallback
        }
    }

    private fun ensureDocumentLoaded(uri: Uri) {
        if (activeDocument != null && uri == currentUri) {
            return
        }

        closeDocumentInternal()

        try {
            val memorySetting = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

            val targetFile = if (uri.scheme == "file" && uri.path != null) {
                File(uri.path!!)
            } else {
                val temp = File(context.cacheDir, "extractor_temp_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output ->
                        input.copyTo(output)
                    }
                }
                cachedPdfFile = temp
                temp
            }

            if (targetFile.exists() && targetFile.length() > 0) {
                activeDocument = PDDocument.load(targetFile, memorySetting)
                currentUri = uri
                Log.d(tag, "Successfully loaded PDDocument (${activeDocument?.numberOfPages} pages) using disk scratch memory")
            } else {
                Log.w(tag, "Target file does not exist or is empty: ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to load document into PDFBox", e)
        }
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
                val sentenceWords = words.filter { it.charStart >= start && it.charEnd <= end }

                val lineBounds = if (sentenceWords.isNotEmpty()) {
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
        return pageTextCache.get(pageIndex)
    }

    private fun closeDocumentInternal() {
        try {
            activeDocument?.close()
            activeDocument = null
            currentUri = null
            cachedPdfFile?.let {
                if (it.exists()) it.delete()
            }
            cachedPdfFile = null
        } catch (e: Exception) {
            Log.e(tag, "Error closing PDDocument", e)
        }
    }

    fun clear() {
        pageTextCache.evictAll()
    }

    fun close() {
        clear()
        closeDocumentInternal()
    }
}
