package com.novapdf.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.novapdf.reader.model.PageTextContent
import com.novapdf.reader.model.PdfSentence
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

    private var tts: TextToSpeech? = null
    private var isInitialized = false

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
    private var sleepTimerJob: Job? = null

    init {
        SpeechForegroundService.callback = this
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setOnUtteranceProgressListener(createUtteranceProgressListener())
            loadAvailableVoices()
            applySettings(_settings.value)
        }
    }

    private fun loadAvailableVoices() {
        val voiceList = ArrayList<TtsVoiceOption>()
        try {
            val voices = tts?.voices ?: emptySet()
            for (v in voices) {
                if (!v.isNetworkConnectionRequired) {
                    val locale = v.locale
                    voiceList.add(
                        TtsVoiceOption(
                            name = v.name,
                            locale = locale,
                            displayName = "${locale.displayLanguage} - ${v.name.substringAfterLast("-", "Default")}",
                            countryName = locale.displayCountry,
                            languageName = locale.displayLanguage,
                            isNetworkConnectionRequired = v.isNetworkConnectionRequired,
                            quality = v.quality
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sort voices alphabetically by language, then country
        _availableVoices.value = voiceList.sortedWith(
            compareBy({ it.languageName }, { it.countryName }, { it.name })
        )
    }

    fun setPageContent(pageIndex: Int, content: PageTextContent) {
        pageContentMap[pageIndex] = content
    }

    fun play(pageIndex: Int? = null, sentenceIndex: Int? = null) {
        if (!isInitialized) return

        if (pageIndex != null) currentPageIndex = pageIndex
        if (sentenceIndex != null) currentSentenceIndex = sentenceIndex

        val content = pageContentMap[currentPageIndex]
        if (content == null || content.sentences.isEmpty()) {
            return
        }

        if (currentSentenceIndex >= content.sentences.size) {
            currentSentenceIndex = 0
        }

        speakCurrentSentence()
        SpeechForegroundService.startService(context)
    }

    fun pause() {
        tts?.stop()
        _playbackState.value = PlaybackState.PAUSED
    }

    fun togglePlayPause() {
        if (_playbackState.value == PlaybackState.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    fun stop() {
        tts?.stop()
        _playbackState.value = PlaybackState.IDLE
        _currentPosition.value = SpeechPosition()
        SpeechForegroundService.stopService(context)
    }

    fun skipNext() {
        val content = pageContentMap[currentPageIndex] ?: return
        if (currentSentenceIndex + 1 < content.sentences.size) {
            currentSentenceIndex++
            speakCurrentSentence()
        } else {
            // End of page, advance to next page if available
            val nextPage = currentPageIndex + 1
            if (pageContentMap.containsKey(nextPage)) {
                currentPageIndex = nextPage
                currentSentenceIndex = 0
                onPageChangeRequested?.invoke(nextPage)
                speakCurrentSentence()
            } else {
                stop()
            }
        }
    }

    fun skipPrevious() {
        val content = pageContentMap[currentPageIndex] ?: return
        if (currentSentenceIndex > 0) {
            currentSentenceIndex--
            speakCurrentSentence()
        } else if (currentPageIndex > 0) {
            val prevPage = currentPageIndex - 1
            val prevContent = pageContentMap[prevPage]
            if (prevContent != null && prevContent.sentences.isNotEmpty()) {
                currentPageIndex = prevPage
                currentSentenceIndex = (prevContent.sentences.size - 1).coerceAtLeast(0)
                onPageChangeRequested?.invoke(prevPage)
                speakCurrentSentence()
            }
        }
    }

    /**
     * Interactive ReadEra Tap-to-Read: starts speaking directly from the tapped line or sentence.
     */
    fun tapToRead(pageIndex: Int, normalizedX: Float, normalizedY: Float) {
        val content = pageContentMap[pageIndex] ?: return
        val targetSentence = content.sentences.firstOrNull { sentence ->
            sentence.bounds.contains(normalizedX, normalizedY) ||
            sentence.lineBounds.any { it.contains(normalizedX, normalizedY) }
        } ?: content.sentences.firstOrNull() ?: return

        currentPageIndex = pageIndex
        currentSentenceIndex = targetSentence.sentenceIndex
        play(pageIndex, currentSentenceIndex)
    }

    private fun speakCurrentSentence() {
        val content = pageContentMap[currentPageIndex] ?: return
        if (currentSentenceIndex !in content.sentences.indices) return

        val sentence = content.sentences[currentSentenceIndex]
        val utteranceId = "utterance_${currentPageIndex}_${currentSentenceIndex}"

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

        tts?.speak(sentence.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
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
            _playbackState.value = PlaybackState.PAUSED
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            // Real-time word boundary callback (Android API 26+)
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

    // Callbacks from Notification / Bluetooth / Lock screen
    override fun onPlayClicked() = play()
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

