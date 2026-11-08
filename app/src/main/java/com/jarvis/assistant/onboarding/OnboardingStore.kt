package com.jarvis.assistant.onboarding

import android.content.Context

/**
 * Persists tutorial progress. It holds no secrets: API keys stay in the existing encrypted AppPreferences.
 */
class OnboardingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): OnboardingProgress = OnboardingProgress.decode(prefs.getString(KEY_PROGRESS, null))

    fun save(progress: OnboardingProgress) {
        prefs.edit().putString(KEY_PROGRESS, progress.encode()).apply()
    }

    var launchState: LaunchState
        get() = LaunchState.values().firstOrNull { it.name == prefs.getString(KEY_LAUNCH, null) } ?: LaunchState.UNDECIDED
        set(value) {
            prefs.edit().putString(KEY_LAUNCH, value.name).apply()
        }

    /**
     * True when the tutorial should open by itself. The first call decides once whether this is a new user or
     * someone who finished the older welcome before this tutorial existed.
     */
    fun shouldAutoLaunch(legacyWelcomeAlreadySeen: Boolean): Boolean {
        var state = launchState
        if (state == LaunchState.UNDECIDED) {
            state = OnboardingGate.initialState(legacyWelcomeAlreadySeen)
            launchState = state
        }
        return state == LaunchState.PENDING
    }

    /** Ends the tutorial for good: it no longer opens on its own, and the walk-through is recorded as finished. */
    fun markFinished(progress: OnboardingProgress): OnboardingProgress {
        val done = progress.finish()
        save(done)
        launchState = LaunchState.DONE
        return done
    }

    /** Replay from Settings: starts from the top without changing whether the tutorial auto-opens. */
    fun beginReplay(): OnboardingProgress {
        val fresh = OnboardingProgress()
        save(fresh)
        return fresh
    }

    fun wasAsked(key: PermissionKey): Boolean =
        prefs.getStringSet(KEY_ASKED, emptySet())?.contains(key.name) == true

    fun markAsked(key: PermissionKey) {
        val updated = HashSet(prefs.getStringSet(KEY_ASKED, emptySet()) ?: emptySet())
        updated.add(key.name)
        prefs.edit().putStringSet(KEY_ASKED, updated).apply()
    }

    var profileSaved: Boolean
        get() = prefs.getBoolean(KEY_PROFILE_SAVED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PROFILE_SAVED, value).apply()
        }

    var lastApiCheck: ApiCheckRecord?
        get() {
            val raw = prefs.getString(KEY_API_CHECK, null) ?: return null
            val parts = raw.split('|')
            if (parts.size != 2) return null
            val at = parts[1].toLongOrNull() ?: return null
            return ApiCheckRecord(parts[0] == "1", at)
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_API_CHECK)
            else editor.putString(KEY_API_CHECK, (if (value.ok) "1" else "0") + "|" + value.at)
            editor.apply()
        }

    private companion object {
        const val NAME = "jarvis_onboarding"
        const val KEY_PROGRESS = "progress"
        const val KEY_LAUNCH = "launch_state"
        const val KEY_ASKED = "permissions_asked"
        const val KEY_PROFILE_SAVED = "profile_saved"
        const val KEY_API_CHECK = "api_check"
    }
}
