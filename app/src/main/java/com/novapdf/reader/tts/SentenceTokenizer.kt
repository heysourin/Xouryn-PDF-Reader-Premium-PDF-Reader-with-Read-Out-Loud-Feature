package com.novapdf.reader.tts

import java.text.BreakIterator
import java.util.Locale

object SentenceTokenizer {

    data class TokenizedSentence(
        val index: Int,
        val text: String,
        val startOffset: Int,
        val endOffset: Int
    )

    fun splitIntoSentences(text: String, locale: Locale = Locale.getDefault()): List<TokenizedSentence> {
        val iterator = BreakIterator.getSentenceInstance(locale)
        iterator.setText(text)

        val sentences = ArrayList<TokenizedSentence>()
        var start = iterator.first()
        var end = iterator.next()
        var index = 0

        while (end != BreakIterator.DONE) {
            val sentenceStr = text.substring(start, end).trim()
            if (sentenceStr.isNotEmpty()) {
                sentences.add(
                    TokenizedSentence(
                        index = index++,
                        text = sentenceStr,
                        startOffset = start,
                        endOffset = end
                    )
                )
            }
            start = end
            end = iterator.next()
        }

        return sentences
    }
}
