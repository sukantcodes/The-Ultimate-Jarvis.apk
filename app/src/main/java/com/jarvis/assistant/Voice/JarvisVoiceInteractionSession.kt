package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.ui.home.MainActivity

class JarvisVoiceInteractionSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        setContentView(createAssistantView())
    }

    override fun onCreateContentView(): View {
        return createAssistantView()
    }

    private fun createAssistantView(): View {
        val layout = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(12, 18, 32))
        }

        val title = TextView(appContext).apply {
            text = "JARVIS"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(appContext).apply {
            text = "Digital Assistant"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val openButton = Button(appContext).apply {
            text = "Open JARVIS"
            setOnClickListener {
                val intent = Intent(
                    appContext,
                    MainActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
            }
        }

        layout.addView(title)
        layout.addView(subtitle)

        layout.addView(
            openButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
        )

        return layout
    }
}
