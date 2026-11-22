package com.jarvis.assistant.ui.chat

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.res.ResourcesCompat
import com.jarvis.assistant.R

/** The three spark animations, each a frame list played at the original 125 ms per frame. */
enum class SparkState(val drawableRes: Int) {
    THINKING(R.drawable.claude_spark_animated_thinking),
    WRITING(R.drawable.claude_spark_animated_writing),
    SHIMMER(R.drawable.claude_spark_animated_shimmer)
}

/**
 * Plays the spark frame animation for a [SparkState]. Setting [state] to null stops and clears it.
 * Honors the system "remove animations" setting by holding the first frame.
 */
class SparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var state: SparkState? = null
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    private fun apply() {
        val s = state
        if (s == null) {
            (drawable as? AnimationDrawable)?.stop()
            setImageDrawable(null)
            return
        }
        val d = try {
            ResourcesCompat.getDrawable(resources, s.drawableRes, context.theme)?.mutate() as? AnimationDrawable
        } catch (_: Throwable) { null }
        setImageDrawable(d)
        if (d == null) return
        if (GlassMotion.animationsOn(this)) { d.isOneShot = false; d.start() } else d.selectDrawable(0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val d = drawable as? AnimationDrawable
        if (d != null && !d.isRunning && GlassMotion.animationsOn(this)) d.start()
    }

    override fun onDetachedFromWindow() {
        (drawable as? AnimationDrawable)?.stop()
        super.onDetachedFromWindow()
    }
}
