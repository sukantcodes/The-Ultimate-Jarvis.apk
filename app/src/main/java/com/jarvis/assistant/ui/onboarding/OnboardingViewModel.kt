package com.jarvis.assistant.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.network.ApiKeyCheck
import com.jarvis.assistant.network.ApiKeyValidator
import com.jarvis.assistant.onboarding.ApiCheckRecord
import com.jarvis.assistant.onboarding.LiveStatus
import com.jarvis.assistant.onboarding.OnboardingProgress
import com.jarvis.assistant.onboarding.OnboardingStatusReader
import com.jarvis.assistant.onboarding.OnboardingStep
import com.jarvis.assistant.onboarding.OnboardingStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the API step shows. It never contains the key. */
data class ApiUiState(val testing: Boolean = false, val message: String? = null, val ok: Boolean? = null)

/**
 * Single source of truth for the tutorial. Progress is written to [OnboardingStore] on every change, so the
 * tutorial resumes exactly where it stopped after rotation, process death or a trip to Android Settings.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    val store = OnboardingStore(app)

    private val _progress = MutableStateFlow(store.load())
    val progress: StateFlow<OnboardingProgress> = _progress.asStateFlow()

    private val _live = MutableStateFlow(OnboardingStatusReader.read(app, store))
    val live: StateFlow<LiveStatus> = _live.asStateFlow()

    private val _api = MutableStateFlow(ApiUiState())
    val api: StateFlow<ApiUiState> = _api.asStateFlow()

    // Edits that are staged but not yet saved. They live only in memory, so rotation keeps them.
    var stagedName: String? = null
    var stagedPhotoUri: String? = null
    var stagedPhotoRemoved: Boolean = false

    /** The key being typed. In memory only: it is never written to saved state, logs or disk by the tutorial. */
    var draftKey: String = ""
    private var lastTestedKey: String? = null
    private var lastTestOk: Boolean = false

    fun refresh() {
        _live.value = OnboardingStatusReader.read(getApplication(), store)
    }

    // ---- progress ---------------------------------------------------------------------------------------

    private fun update(block: (OnboardingProgress) -> OnboardingProgress) {
        val next = block(_progress.value)
        _progress.value = next
        store.save(next)
    }

    /** Moves on from [step]: recorded as completed when its real condition holds, otherwise as skipped. */
    fun settle(step: OnboardingStep, satisfied: Boolean) {
        update { if (satisfied) it.complete(step) else it.skip(step) }
        returnToFinalIfJumped()
    }

    fun skip(step: OnboardingStep) {
        update { it.skip(step) }
        returnToFinalIfJumped()
    }

    fun back() {
        if (jumpedFromFinal) {
            returnToFinalIfJumped()
            return
        }
        update { it.back() }
    }

    fun goTo(step: OnboardingStep) = update { it.goTo(step) }

    /** True while the person is fixing one item from the final system check: finishing it returns to the check. */
    private var jumpedFromFinal = false

    fun jumpFromFinal(step: OnboardingStep) {
        jumpedFromFinal = true
        update { it.goTo(step) }
    }

    private fun returnToFinalIfJumped() {
        if (!jumpedFromFinal) return
        jumpedFromFinal = false
        update { it.goTo(OnboardingStep.FINAL) }
    }

    /** Ends the tutorial (finished or skipped): it stops opening by itself and can be replayed from Settings. */
    fun finishTutorial() {
        _progress.value = store.markFinished(_progress.value)
    }

    // ---- API key ----------------------------------------------------------------------------------------

    fun testKey(key: String) {
        if (_api.value.testing) return
        _api.value = ApiUiState(testing = true)
        viewModelScope.launch {
            val check = ApiKeyValidator.validate(key)
            val ok = check is ApiKeyCheck.Valid
            if (check !is ApiKeyCheck.Empty) {
                lastTestedKey = key.trim()
                lastTestOk = ok
                store.lastApiCheck = ApiCheckRecord(ok, System.currentTimeMillis())
            }
            _api.value = ApiUiState(false, ApiKeyValidator.describe(check), if (check is ApiKeyCheck.Empty) null else ok)
            refresh()
        }
    }

    /** Stores the key through the existing Keystore-backed preferences. Returns an error message, or null on success. */
    fun saveKey(key: String): String? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "Paste an API key first."
        try {
            JarvisApp.instance.preferences.apiKey = trimmed
        } catch (e: IllegalStateException) {
            return e.message ?: "Secure key storage is unavailable on this device."
        } catch (e: Exception) {
            return "Couldn't save the key securely."
        }
        // A saved key is only called verified when this exact key passed a test.
        if (!(lastTestOk && lastTestedKey == trimmed)) store.lastApiCheck = null
        JarvisConversationService.clearLiveError()
        JarvisConversationService.restartSession()
        draftKey = ""
        refresh()
        return null
    }
}
