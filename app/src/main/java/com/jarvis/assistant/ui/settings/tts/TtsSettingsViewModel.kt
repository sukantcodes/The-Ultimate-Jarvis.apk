package com.jarvis.assistant.ui.settings.tts

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.tts.engine.JarvisVoiceDispatcher
import com.jarvis.assistant.tts.engine.TtsModelDownloader
import com.jarvis.assistant.tts.model.DownloadState
import com.jarvis.assistant.tts.model.SpeakerProfile
import com.jarvis.assistant.tts.model.VoiceEngineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel managing TTS engine states, model downloads, speaker profiles, and live amplitude updates.
 */
class TtsSettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TtsSettingsViewModel"
    }

    private val dispatcher = JarvisVoiceDispatcher.getInstance(application)
    private val downloader = TtsModelDownloader(application)

    private val _selectedEngine = MutableStateFlow(dispatcher.getActiveEngineType())
    val selectedEngine: StateFlow<VoiceEngineType> = _selectedEngine.asStateFlow()

    private val _selectedSpeaker = MutableStateFlow(dispatcher.getActiveSpeaker())
    val selectedSpeaker: StateFlow<SpeakerProfile> = _selectedSpeaker.asStateFlow()

    val downloadState: StateFlow<DownloadState> = downloader.downloadState

    private val _isEngineInstalled = MutableStateFlow(
        TtsModelDownloader.isEngineInstalled(application, _selectedEngine.value)
    )
    val isEngineInstalled: StateFlow<Boolean> = _isEngineInstalled.asStateFlow()

    private val _speechSpeed = MutableStateFlow(dispatcher.getSpeechSpeed())
    val speechSpeed: StateFlow<Float> = _speechSpeed.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val amplitudeListener: (Float) -> Unit = { amp ->
        _amplitude.value = amp
    }

    private val speakingListener: (Boolean) -> Unit = { speaking ->
        _isSpeaking.value = speaking
    }

    init {
        dispatcher.addAmplitudeListener(amplitudeListener)
        dispatcher.addSpeakingStateListener(speakingListener)
        refreshInstallationState()
    }

    fun refreshInstallationState() {
        val installed = TtsModelDownloader.isEngineInstalled(getApplication(), _selectedEngine.value)
        _isEngineInstalled.value = installed
    }

    fun selectEngine(engineType: VoiceEngineType) {
        _selectedEngine.value = engineType
        val defaultSpeaker = SpeakerProfile.defaultFor(engineType)
        _selectedSpeaker.value = defaultSpeaker
        refreshInstallationState()
    }

    fun selectSpeaker(speaker: SpeakerProfile) {
        _selectedSpeaker.value = speaker
    }

    fun setSpeed(speed: Float) {
        _speechSpeed.value = speed
        dispatcher.setSpeechSpeed(speed)
    }

    fun deployCurrentEngine() {
        val engine = _selectedEngine.value
        if (!engine.isLocal) return

        viewModelScope.launch {
            downloader.downloadAndInstall(engine) {
                refreshInstallationState()
                dispatcher.setActiveEngineType(engine)
                val speakerToSet = if (_selectedSpeaker.value.engineType == engine) {
                    _selectedSpeaker.value
                } else {
                    SpeakerProfile.defaultFor(engine).also { _selectedSpeaker.value = it }
                }
                dispatcher.setActiveSpeaker(speakerToSet)
                reportEngineHealth(engine)
                try {
                    testVoiceValidation()
                } catch (e: Exception) {
                    Log.w(TAG, "Test voice validation threw exception following deploy", e)
                }
            }
        }
    }

    /**
     * Starts the chosen offline voice and, if it cannot start, tells the user why instead of staying silent.
     * Runs off the main thread because loading the model takes a moment.
     */
    private fun reportEngineHealth(engine: VoiceEngineType) {
        if (!engine.isLocal) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val started = try {
                dispatcher.sherpaEngine.initializeEngine(engine)
            } catch (e: Throwable) {
                false
            }
            if (!started) {
                val reason = dispatcher.sherpaEngine.lastError ?: "unknown reason"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        getApplication<Application>(),
                        "Offline voice could not start: $reason",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun testVoiceValidation(
        phrase: String = "Systems initialized. Voice parameters verified, Sir."
    ) {
        try {
            val speaker = _selectedSpeaker.value
            dispatcher.speak(phrase, overrideSpeaker = speaker)
        } catch (e: Exception) {
            Log.e(TAG, "Speech execution error during voice validation", e)
        }
    }

    fun saveAndActivate(onSaved: (() -> Unit)? = null) {
        val engine = _selectedEngine.value
        val speaker = _selectedSpeaker.value
        dispatcher.setActiveEngineType(engine)
        dispatcher.setActiveSpeaker(speaker)
        dispatcher.setSpeechSpeed(_speechSpeed.value)
        reportEngineHealth(engine)
        try {
            testVoiceValidation()
        } catch (e: Exception) {
            Log.w(TAG, "Voice validation notice during save & activate", e)
        }
        onSaved?.invoke()
    }

    fun deactivateOfflineTts(onDeactivated: (() -> Unit)? = null) {
        viewModelScope.launch {
            dispatcher.deactivateOfflineTts()
            _selectedEngine.value = VoiceEngineType.NONE
            _selectedSpeaker.value = SpeakerProfile.defaultFor(VoiceEngineType.NONE)
            refreshInstallationState()
            onDeactivated?.invoke()
        }
    }

    override fun onCleared() {
        super.onCleared()
        dispatcher.removeAmplitudeListener(amplitudeListener)
        dispatcher.removeSpeakingStateListener(speakingListener)
    }
}
