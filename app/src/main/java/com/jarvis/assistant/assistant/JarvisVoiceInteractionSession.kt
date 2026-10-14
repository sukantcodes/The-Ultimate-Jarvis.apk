package com.jarvis.assistant.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.jarvis.assistant.background.JarvisConversationService
import com.jarvis.assistant.ui.overlay.GlowOverlay

/**
 * Runs when the user invokes the default assistant (power-button long press, corner swipe, or the
 * assistant gesture). It wakes Jarvis, shows the glow for 5 seconds, and gets out of the way.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            val intent = Intent(context, JarvisConversationService::class.java)
                .setAction(JarvisConversationService.ACTION_START_CONVERSATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            GlowOverlay.show(context)
        } catch (e: Exception) {
            Log.w("JarvisAssistant", "Could not start Jarvis from the assistant gesture: ${e.message}")
        }
        hide()
    }
}
