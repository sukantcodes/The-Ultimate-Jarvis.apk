package com.jarvis.assistant.voice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class JarvisVoiceInteractionSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    override fun onCreateContentView(): View {
        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }

        root.addView(GlowingEdgeView(appContext),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )

        val title = TextView(appContext).apply {
            text = "J A R V I S"
            textSize = 27f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = 10f
        }

        val subtitle = TextView(appContext).apply {
            text = "I'M LISTENING"
            textSize = 12f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
            elevation = 10f
        }

        root.addView(title)
        root.addView(subtitle)

        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
    }

    private class GlowingEdgeView(context: Context) : View(context) {
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 7f
            setShadowLayer(28f, 0f, 0f, Color.BLUE)
        }

        private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 220, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        init {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val inset = 12f
            val rect = RectF(
                inset,
                inset,
                width - inset,
                height - inset
            )

            canvas.drawRoundRect(rect, 38f, 38f, glowPaint)
            canvas.drawRoundRect(rect, 38f, 38f, edgePaint)
        }
    }
}
