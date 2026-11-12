package com.jarvis.assistant.ui.settings.tts

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/**
 * Activity host for the Neural Voice Core & Dual-Tier TTS settings HUD.
 */
class TtsSettingsActivity : AppCompatActivity() {

    private var rootFrame: android.widget.FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)

        val frame = android.widget.FrameLayout(this).apply {
            id = android.view.View.generateViewId()
        }
        rootFrame = frame
        setContentView(frame)
        ThemeManager.applyDynamicBackground(frame)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(frame.id, TtsSettingsFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        rootFrame?.let { ThemeManager.applyDynamicBackground(it) }
    }
}
