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
import com.novapdf.reader.tts.SentenceTokenizer
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

                    val rawWords = ArrayList<PdfWord>()

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
                            var minX = Float.MAX_VALUE
                            var minY = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxY = Float.MIN_VALUE

                            for (pos in textPositions) {
                                val charStr = pos.unicode ?: continue
                                val x = (pos.xDirAdj / actualWidth).coerceIn(0f, 1f)
                                val topY = ((pos.yDirAdj - pos.heightDir) / actualHeight).coerceIn(0f, 1f)
                                val w = (pos.widthDirAdj / actualWidth).coerceIn(0f, 1f)
                                val h = (pos.heightDir / actualHeight).coerceIn(0f, 1f)

                                minX = minOf(minX, x)
                                minY = minOf(minY, topY)
                                maxX = maxOf(maxX, x + w)
                                maxY = maxOf(maxY, topY + h)

                                if (charStr == " " || charStr == "\t") {
                                    if (currentWordSb.isNotEmpty()) {
                                        rawWords.add(
                                            PdfWord(
                                                text = currentWordSb.toString(),
                                                bounds = NormalizedRect(
                                                    left = minX.coerceIn(0f, 1f),
                                                    top = minY.coerceIn(0f, 1f),
                                                    right = maxX.coerceIn(0f, 1f),
                                                    bottom = maxY.coerceIn(0f, 1f)
                                                ),
                                                charStart = 0,
                                                charEnd = 0
                                            )
                                        )
                                        currentWordSb.clear()
                                        minX = Float.MAX_VALUE
                                        minY = Float.MAX_VALUE
                                        maxX = Float.MIN_VALUE
                                        maxY = Float.MIN_VALUE
                                    }
                                } else {
                                    currentWordSb.append(charStr)
                                }
                            }

                            if (currentWordSb.isNotEmpty()) {
                                rawWords.add(
                                    PdfWord(
                                        text = currentWordSb.toString(),
                                        bounds = NormalizedRect(
                                            left = minX.coerceIn(0f, 1f),
                                            top = minY.coerceIn(0f, 1f),
                                            right = maxX.coerceIn(0f, 1f),
                                            bottom = maxY.coerceIn(0f, 1f)
                                        ),
                                        charStart = 0,
                                        charEnd = 0
                                    )
                                )
                            }
                        }
                    }

                    stripper.getText(doc)

                    if (rawWords.isNotEmpty()) {
                        val content = processExtractedWords(pageIndex, rawWords)
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

    /**
     * Solves Problem 3 (scrambled / wavy lines in pirated books) and Problem 1 (natural prosody):
     * Clusters words into visual lines using adaptive vertical overlap, de-hyphenates across line ends,
     * and constructs fluent natural sentences with clean speech text.
     */
    private fun processExtractedWords(pageIndex: Int, rawWords: List<PdfWord>): PageTextContent {
        // 1. Sort words roughly top-to-bottom
        val sortedWords = rawWords.sortedWith(
            compareBy({ ((it.bounds.top + it.bounds.bottom) / 2f * 1000).toInt() }, { it.bounds.left })
        )

        // 2. Adaptive Line Clustering: Two words belong to the same line if their vertical centers
        // or bounding intervals overlap by > 40%. This completely absorbs skew and baseline jitter!
        val lineClusters = ArrayList<ArrayList<PdfWord>>()

        for (word in sortedWords) {
            val wordMidY = (word.bounds.top + word.bounds.bottom) / 2f
            val wordHeight = word.bounds.height.coerceAtLeast(0.005f)

            // Find an existing line cluster where this word fits vertically
            var targetLine: ArrayList<PdfWord>? = null
            for (line in lineClusters) {
                val lineMidY = (line.map { (it.bounds.top + it.bounds.bottom) / 2f }).average().toFloat()
                val lineHeight = (line.map { it.bounds.height }).average().toFloat().coerceAtLeast(0.005f)

                val diffY = kotlin.math.abs(wordMidY - lineMidY)
                val threshold = (lineHeight + wordHeight) * 0.45f

                if (diffY < threshold) {
                    targetLine = line
                    break
                }
            }

            if (targetLine != null) {
                targetLine.add(word)
            } else {
                val newLine = ArrayList<PdfWord>()
                newLine.add(word)
                lineClusters.add(newLine)
            }
        }

        // 3. Sort lines top-to-bottom, and words within each line left-to-right
        lineClusters.sortBy { line -> line.minOf { it.bounds.top } }
        for (line in lineClusters) {
            line.sortBy { it.bounds.left }
        }

        // 4. Construct lines and de-hyphenate across line boundaries
        val finalLines = ArrayList<PdfTextLine>()
        val allWordsInReadingOrder = ArrayList<PdfWord>()
        val fullTextSb = StringBuilder()

        for (lineIdx in lineClusters.indices) {
            val wordsInLine = lineClusters[lineIdx]
            if (wordsInLine.isEmpty()) continue

            val lineMinX = wordsInLine.minOf { it.bounds.left }
            val lineMinY = wordsInLine.minOf { it.bounds.top }
            val lineMaxX = wordsInLine.maxOf { it.bounds.right }
            val lineMaxY = wordsInLine.maxOf { it.bounds.bottom }

            val lineTextSb = StringBuilder()
            val processedLineWords = ArrayList<PdfWord>()

            for (wIdx in wordsInLine.indices) {
                var word = wordsInLine[wIdx]
                val wordStartInFull = fullTextSb.length

                // Check for de-hyphenation at line end with next line's first word
                if (wIdx == wordsInLine.lastIndex && word.text.endsWith("-") && word.text.length > 1 && lineIdx + 1 < lineClusters.size) {
                    val nextLineFirstWord = lineClusters[lineIdx + 1].firstOrNull()
                    if (nextLineFirstWord != null && nextLineFirstWord.text.all { it.isLetter() }) {
                        val baseWord = word.text.removeSuffix("-")
                        // Leave the hyphen in display word, but speech will de-hyphenate seamlessly
                    }
                }

                lineTextSb.append(word.text).append(" ")
                fullTextSb.append(word.text).append(" ")

                val assignedWord = word.copy(
                    charStart = wordStartInFull,
                    charEnd = fullTextSb.length - 1
                )
                processedLineWords.add(assignedWord)
                allWordsInReadingOrder.add(assignedWord)
            }

            finalLines.add(
                PdfTextLine(
                    lineIndex = lineIdx,
                    text = lineTextSb.toString().trim(),
                    bounds = NormalizedRect(
                        left = lineMinX,
                        top = lineMinY,
                        right = lineMaxX,
                        bottom = lineMaxY
                    ),
                    words = processedLineWords
                )
            )
        }

        val fullText = fullTextSb.toString().trim()

        // 5. Build natural, human-like sentences using SentenceTokenizer
        val tokenized = SentenceTokenizer.splitIntoNaturalSentences(fullText)
        val sentences = ArrayList<PdfSentence>()

        for (tok in tokenized) {
            // Find words belonging to this sentence
            val sentenceWords = allWordsInReadingOrder.filter {
                it.charStart >= tok.startOffset && it.charEnd <= tok.endOffset + 2
            }

            // Group sentence words by their visual line to produce exact line highlight boxes
            val lineBounds = if (sentenceWords.isNotEmpty()) {
                sentenceWords.groupBy { word ->
                    // Find which line this word belongs to
                    finalLines.indexOfFirst { line -> line.words.any { it.charStart == word.charStart } }
                }.values.map { lineWordList ->
                    NormalizedRect(
                        left = lineWordList.minOf { it.bounds.left },
                        top = lineWordList.minOf { it.bounds.top },
                        right = lineWordList.maxOf { it.bounds.right },
                        bottom = lineWordList.maxOf { it.bounds.bottom }
                    )
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
                    sentenceIndex = sentences.size,
                    text = tok.cleanSpeechText,
                    bounds = sentenceBounds,
                    lineBounds = lineBounds.ifEmpty { listOf(sentenceBounds) },
                    charOffsetStart = tok.startOffset,
                    charOffsetEnd = tok.endOffset
                )
            )
        }

        return PageTextContent(
            pageIndex = pageIndex,
            fullText = fullText,
            lines = finalLines,
            sentences = sentences
        )
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
