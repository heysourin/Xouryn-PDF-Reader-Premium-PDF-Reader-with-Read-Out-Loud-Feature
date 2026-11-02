package com.novapdf.reader.model

import java.util.Locale

/**
 * Real-time Speech Position tracking on a PDF page (ReadEra style).
 */
data class SpeechPosition(
    val pageIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val totalSentencesInPage: Int = 0,
    val currentSentence: PdfSentence? = null,
    val activeLineBounds: List<NormalizedRect> = emptyList(),
    val charStartInSentence: Int = 0,
    val charEndInSentence: Int = 0
)

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED,
    BUFFERING,
    COMPLETED
}

/**
 * Information regarding a selectable TTS voice (Accent, Locale, Name).
 */
data class TtsVoiceOption(
    val name: String,
    val locale: Locale,
    val displayName: String,
    val countryName: String,
    val languageName: String,
    val isNetworkConnectionRequired: Boolean = false,
    val quality: Int = 300 // Voice.QUALITY_NORMAL
) {
    val accentLabel: String
        get() = if (countryName.isNotBlank()) "$languageName ($countryName)" else languageName
}

/**
 * Persistent user settings for TTS playback.
 */
data class TtsSettings(
    val speechRate: Float = 1.0f,       // 0.5f to 3.0f
    val pitch: Float = 1.0f,            // 0.5f to 2.0f
    val selectedVoiceName: String? = null,
    val preferredLocale: Locale = Locale.getDefault(),
    val autoScrollToLine: Boolean = true,
    val autoAdvancePage: Boolean = true,
    val highlightColorArgb: Int = 0x66FFEB3B.toInt(), // Glowing amber yellow
    val sleepTimerRemainingSeconds: Long? = null
)
