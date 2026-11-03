package com.novapdf.reader.tts

import java.util.Locale
import java.util.regex.Pattern

object SentenceTokenizer {

    data class TokenizedSentence(
        val index: Int,
        val text: String,
        val cleanSpeechText: String,
        val startOffset: Int,
        val endOffset: Int
    )

    // Common abbreviations that do NOT end a sentence
    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "vs", "etc", "eg", "ie",
        "no", "vol", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec", "st", "ave", "blvd", "rd", "dept", "est", "approx",
        "min", "max", "fig", "al", "inc", "ltd", "co", "corp", "univ", "capt", "col", "gen"
    )

    /**
     * Splits extracted text into natural, complete sentences for human-like TTS reading.
     * Handles abbreviations, initials, decimals, numbers, and de-hyphenation cleanly.
     */
    fun splitIntoNaturalSentences(rawText: String): List<TokenizedSentence> {
        val sentences = ArrayList<TokenizedSentence>()
        if (rawText.isBlank()) return sentences

        val length = rawText.length
        var sentenceStart = 0
        var i = 0

        while (i < length) {
            val c = rawText[i]

            // Check for sentence terminator: '.', '!', '?', or paragraph break
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                var isTerminator = false

                if (c == '!' || c == '?') {
                    // Always terminates sentence (unless part of a duplicate like !!)
                    isTerminator = (i + 1 >= length || rawText[i + 1] == ' ' || rawText[i + 1] == '\n' || rawText[i + 1] == '"' || rawText[i + 1] == '”')
                } else if (c == '.') {
                    // Check if it's a decimal number (e.g. 3.14)
                    val isDecimal = (i > 0 && rawText[i - 1].isDigit()) && (i + 1 < length && rawText[i + 1].isDigit())

                    // Check if it's an abbreviation
                    val wordBefore = extractPrecedingWord(rawText, i).lowercase(Locale.ROOT)
                    val isAbbrev = ABBREVIATIONS.contains(wordBefore)

                    // Check if it's an initial (e.g. "J. K. Rowling")
                    val isInitial = wordBefore.length == 1 && wordBefore[0].isLetter()

                    // Check if next char is lowercase (continuation)
                    var nextNonSpace = i + 1
                    while (nextNonSpace < length && (rawText[nextNonSpace] == ' ' || rawText[nextNonSpace] == '"' || rawText[nextNonSpace] == '”')) {
                        nextNonSpace++
                    }
                    val isFollowedByLowercase = nextNonSpace < length && rawText[nextNonSpace].isLowerCase()

                    if (!isDecimal && !isAbbrev && !isInitial && !isFollowedByLowercase) {
                        isTerminator = true
                    }
                } else if (c == '\n') {
                    // Paragraph break (double newline) terminates a sentence
                    if (i + 1 < length && rawText[i + 1] == '\n') {
                        isTerminator = true
                    }
                }

                if (isTerminator) {
                    // Include any closing quotes/brackets immediately following punctuation
                    var sentenceEnd = i + 1
                    while (sentenceEnd < length && (rawText[sentenceEnd] == '"' || rawText[sentenceEnd] == '”' || rawText[sentenceEnd] == ')' || rawText[sentenceEnd] == ']')) {
                        sentenceEnd++
                    }

                    val candidate = rawText.substring(sentenceStart, sentenceEnd).trim()
                    if (candidate.length >= 2 && candidate.any { it.isLetterOrDigit() }) {
                        val cleanSpeech = sanitizeForSpeech(candidate)
                        if (cleanSpeech.isNotBlank()) {
                            sentences.add(
                                TokenizedSentence(
                                    index = sentences.size,
                                    text = candidate,
                                    cleanSpeechText = cleanSpeech,
                                    startOffset = sentenceStart,
                                    endOffset = sentenceEnd
                                )
                            )
                        }
                    }

                    sentenceStart = sentenceEnd
                    i = sentenceEnd
                    continue
                }
            }
            i++
        }

        // Remainder
        if (sentenceStart < length) {
            val candidate = rawText.substring(sentenceStart).trim()
            if (candidate.length >= 2 && candidate.any { it.isLetterOrDigit() }) {
                val cleanSpeech = sanitizeForSpeech(candidate)
                if (cleanSpeech.isNotBlank()) {
                    sentences.add(
                        TokenizedSentence(
                            index = sentences.size,
                            text = candidate,
                            cleanSpeechText = cleanSpeech,
                            startOffset = sentenceStart,
                            endOffset = length
                        )
                    )
                }
            }
        }

        return sentences
    }

    private fun extractPrecedingWord(text: String, index: Int): String {
        var start = index - 1
        while (start >= 0 && text[start].isLetter()) {
            start--
        }
        return text.substring(start + 1, index)
    }

    /**
     * Sanitizes raw text into natural, fluent speech text for the Android TTS engine.
     * De-hyphenates, replaces dashes with pauses, and removes OCR artifacts.
     */
    fun sanitizeForSpeech(text: String): String {
        var cleaned = text
            // De-hyphenate across lines or words: e.g. "Bud- dhist" -> "Buddhist"
            .replace(Regex("(\\w+)-\\s+(\\w+)"), "$1$2")
            // Replace em-dashes and multiple hyphens with natural breath pauses
            .replace("—", ", ")
            .replace("–", ", ")
            .replace("--", ", ")
            // Normalize quotes and ligatures
            .replace("“", "\"").replace("”", "\"")
            .replace("‘", "'").replace("’", "'")
            .replace("ﬁ", "fi").replace("ﬂ", "fl")
            // Remove orphan bullet characters and page numbers
            .replace(Regex("^[•·*\\-–—]+\\s*"), "")
            // Replace newlines and extra spaces with a single clean space
            .replace(Regex("\\s+"), " ")
            .trim()

        // Ensure sentence concludes cleanly so TTS applies natural pitch inflection
        if (cleaned.isNotEmpty() && !cleaned.endsWith(".") && !cleaned.endsWith("!") && !cleaned.endsWith("?") && !cleaned.endsWith(",")) {
            cleaned = "$cleaned."
        }

        return cleaned
    }
}
