package com.novapdf.reader.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.novapdf.reader.engine.PdfTextExtractor
import com.novapdf.reader.model.PageTextContent
import com.novapdf.reader.model.PlaybackState
import com.novapdf.reader.model.SpeechPosition
import com.novapdf.reader.model.TtsSettings
import com.novapdf.reader.model.TtsVoiceOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class TtsController(
    private val context: Context,
    private val scope: CoroutineScope
) : TextToSpeech.OnInitListener, SpeechForegroundService.ServiceActionCallback {

    private val tag = "NovaPDF_TTS"
    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _currentPosition = MutableStateFlow(SpeechPosition())
    val currentPosition: StateFlow<SpeechPosition> = _currentPosition.asStateFlow()

    private val _settings = MutableStateFlow(TtsSettings())
    val settings: StateFlow<TtsSettings> = _settings.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceOption>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceOption>> = _availableVoices.asStateFlow()

    // Page text buffer: PageIndex -> PageTextContent
    private val pageContentMap = HashMap<Int, PageTextContent>()
    private var currentPageIndex = 0
    private var currentSentenceIndex = 0

    var onPageChangeRequested: ((Int) -> Unit)? = null
    var onTextExtractionRequired: (suspend (Int) -> PageTextContent)? = null
    private var sleepTimerJob: Job? = null

    init {
        SpeechForegroundService.callback = this
        initTtsEngine()
    }

    private fun initTtsEngine() {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to instantiate TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            Log.d(tag, "TextToSpeech engine initialized successfully")

            tts?.let { engine ->
                // Set AudioAttributes for media speech
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )

                // Set language: try device default first, fallback to US English
                val defaultLocale = Locale.getDefault()
                val langResult = engine.setLanguage(defaultLocale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(tag, "Default locale not supported, falling back to Locale.US")
                    engine.setLanguage(Locale.US)
                }

                engine.setOnUtteranceProgressListener(createUtteranceProgressListener())
                loadAvailableVoices()
                applySettings(_settings.value)
            }
        } else {
            Log.e(tag, "TextToSpeech init failed with status: $status")
        }
    }

    private fun loadAvailableVoices() {
        val voiceList = ArrayList<TtsVoiceOption>()
        try {
            val voices = tts?.voices
            if (!voices.isNullOrEmpty()) {
                for (v in voices) {
                    val locale = v.locale ?: Locale.US
                    voiceList.add(
                        TtsVoiceOption(
                            name = v.name,
                            locale = locale,
                            displayName = "${locale.displayLanguage} (${locale.country.ifBlank { "Default" }})",
                            countryName = locale.displayCountry,
                            languageName = locale.displayLanguage,
                            isNetworkConnectionRequired = v.isNetworkConnectionRequired,
                            quality = v.quality
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not load voice list", e)
        }

        if (voiceList.isEmpty()) {
            voiceList.add(
                TtsVoiceOption(
                    name = "default",
                    locale = Locale.getDefault(),
                    displayName = "System Default Voice",
                    countryName = Locale.getDefault().displayCountry,
                    languageName = Locale.getDefault().displayLanguage
                )
            )
        }

        _availableVoices.value = voiceList.distinctBy { it.name }.sortedWith(
            compareBy({ it.languageName }, { it.countryName }, { it.name })
        )

        // Automatically choose the highest quality voice for natural human reading
        if (_settings.value.selectedVoiceName == null) {
            val defaultLocale = Locale.getDefault()
            val bestVoice = tts?.voices?.filter {
                it.locale != null && (it.locale.language == defaultLocale.language || it.locale == Locale.US)
            }?.maxByOrNull { it.quality } ?: tts?.defaultVoice

            if (bestVoice != null) {
                try {
                    tts?.voice = bestVoice
                    _settings.value = _settings.value.copy(selectedVoiceName = bestVoice.name)
                } catch (e: Exception) {
                    Log.w(tag, "Could not set best voice", e)
                }
            }
        }
    }

    fun setPageContent(pageIndex: Int, content: PageTextContent) {
        pageContentMap[pageIndex] = content
    }

    fun play(pageIndex: Int? = null, sentenceIndex: Int? = null) {
        if (pageIndex != null) currentPageIndex = pageIndex
        if (sentenceIndex != null) currentSentenceIndex = sentenceIndex

        scope.launch(Dispatchers.Main) {
            // If page text not cached yet, request immediate extraction
            var content = pageContentMap[currentPageIndex]
            if (content == null || content.sentences.isEmpty()) {
                _playbackState.value = PlaybackState.BUFFERING
                content = onTextExtractionRequired?.invoke(currentPageIndex)
                if (content != null) {
                    pageContentMap[currentPageIndex] = content
                }
            }

            if (content == null || content.sentences.isEmpty()) {
                Log.w(tag, "No sentences available to speak for page $currentPageIndex")
                _playbackState.value = PlaybackState.IDLE
                return@launch
            }

            if (currentSentenceIndex !in content.sentences.indices) {
                currentSentenceIndex = 0
            }

            speakCurrentSentence()
            SpeechForegroundService.startService(context)
        }
    }

    fun pause() {
        tts?.stop()
        _playbackState.value = PlaybackState.PAUSED
    }

    fun togglePlayPause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            pause()
        } else {
            play(currentPageIndex, currentSentenceIndex)
        }
    }

    fun stop() {
        tts?.stop()
        _playbackState.value = PlaybackState.IDLE
        _currentPosition.value = SpeechPosition()
        SpeechForegroundService.stopService(context)
    }

    fun skipNext() {
        val content = pageContentMap[currentPageIndex]
        if (content != null && currentSentenceIndex + 1 < content.sentences.size) {
            currentSentenceIndex++
            speakCurrentSentence()
        } else {
            val nextPage = currentPageIndex + 1
            currentPageIndex = nextPage
            currentSentenceIndex = 0
            onPageChangeRequested?.invoke(nextPage)
            play(nextPage, 0)
        }
    }

    fun skipPrevious() {
        if (currentSentenceIndex > 0) {
            currentSentenceIndex--
            speakCurrentSentence()
        } else if (currentPageIndex > 0) {
            val prevPage = currentPageIndex - 1
            currentPageIndex = prevPage
            onPageChangeRequested?.invoke(prevPage)
            val prevContent = pageContentMap[prevPage]
            currentSentenceIndex = if (prevContent != null && prevContent.sentences.isNotEmpty()) {
                prevContent.sentences.size - 1
            } else {
                0
            }
            play(prevPage, currentSentenceIndex)
        }
    }

    fun tapToRead(pageIndex: Int, normalizedX: Float, normalizedY: Float) {
        scope.launch(Dispatchers.Main) {
            var content = pageContentMap[pageIndex]
            if (content == null || content.sentences.isEmpty()) {
                content = onTextExtractionRequired?.invoke(pageIndex)
                if (content != null) pageContentMap[pageIndex] = content
            }
            if (content == null || content.sentences.isEmpty()) return@launch

            val targetSentence = content.sentences.firstOrNull { sentence ->
                sentence.bounds.contains(normalizedX, normalizedY) ||
                sentence.lineBounds.any { it.contains(normalizedX, normalizedY) }
            } ?: content.sentences.firstOrNull() ?: return@launch

            currentPageIndex = pageIndex
            currentSentenceIndex = targetSentence.sentenceIndex
            play(pageIndex, currentSentenceIndex)
        }
    }

    private fun speakCurrentSentence() {
        val engine = tts
        if (engine == null || !isInitialized) {
            Log.w(tag, "TTS engine not ready yet")
            return
        }

        val content = pageContentMap[currentPageIndex] ?: return
        if (currentSentenceIndex !in content.sentences.indices) return

        val sentence = content.sentences[currentSentenceIndex]
        val utteranceId = "utterance_${currentPageIndex}_${currentSentenceIndex}_${System.currentTimeMillis()}"

        _currentPosition.value = SpeechPosition(
            pageIndex = currentPageIndex,
            sentenceIndex = currentSentenceIndex,
            totalSentencesInPage = content.sentences.size,
            currentSentence = sentence,
            activeLineBounds = sentence.lineBounds
        )
        _playbackState.value = PlaybackState.PLAYING

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val speechText = SentenceTokenizer.sanitizeForSpeech(sentence.text)

        val speakResult = engine.speak(speechText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (speakResult != TextToSpeech.SUCCESS) {
            Log.e(tag, "engine.speak failed with code: $speakResult. Trying fallback locale...")
            engine.setLanguage(Locale.US)
            engine.speak(speechText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    private fun createUtteranceProgressListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _playbackState.value = PlaybackState.PLAYING
        }

        override fun onDone(utteranceId: String?) {
            scope.launch(Dispatchers.Main) {
                if (_settings.value.autoAdvancePage) {
                    skipNext()
                } else {
                    _playbackState.value = PlaybackState.PAUSED
                }
            }
        }

        override fun onError(utteranceId: String?) {
            Log.e(tag, "UtteranceProgressListener error on $utteranceId")
            _playbackState.value = PlaybackState.PAUSED
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            val pos = _currentPosition.value
            _currentPosition.value = pos.copy(
                charStartInSentence = start,
                charEndInSentence = end
            )
        }
    }

    fun setSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.5f, 3.0f)
        _settings.value = _settings.value.copy(speechRate = clamped)
        tts?.setSpeechRate(clamped)
    }

    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        _settings.value = _settings.value.copy(pitch = clamped)
        tts?.setPitch(clamped)
    }

    fun setVoice(voiceName: String) {
        _settings.value = _settings.value.copy(selectedVoiceName = voiceName)
        tts?.voices?.find { it.name == voiceName }?.let { voice ->
            tts?.voice = voice
        }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _settings.value = _settings.value.copy(sleepTimerRemainingSeconds = null)
            return
        }

        sleepTimerJob = scope.launch {
            var remaining = minutes * 60L
            while (remaining > 0) {
                _settings.value = _settings.value.copy(sleepTimerRemainingSeconds = remaining)
                delay(1000)
                remaining--
            }
            _settings.value = _settings.value.copy(sleepTimerRemainingSeconds = null)
            stop()
        }
    }

    private fun applySettings(settings: TtsSettings) {
        tts?.setSpeechRate(settings.speechRate)
        tts?.setPitch(settings.pitch)
        settings.selectedVoiceName?.let { name ->
            tts?.voices?.find { it.name == name }?.let { tts?.voice = it }
        }
    }

    override fun onPlayClicked() = play(currentPageIndex, currentSentenceIndex)
    override fun onPauseClicked() = pause()
    override fun onPrevClicked() = skipPrevious()
    override fun onNextClicked() = skipNext()
    override fun onStopClicked() = stop()

    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
