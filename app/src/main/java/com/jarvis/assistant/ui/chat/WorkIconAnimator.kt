package com.jarvis.assistant.ui.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R

object WorkIconAnimator {

    /**
     * Applies the distinct Claude-style work icon and reactive animation to the target ImageView.
     */
    fun applyWorkAnimation(
        iconView: ImageView,
        frameView: View? = null,
        type: WorkActionType,
        isWriting: Boolean
    ) {
        val context = iconView.context
        // Streaming re-binds the card on every update; restarting the animation each time makes it stutter.
        val stateKey = type.name + ":" + isWriting
        val stillRunning = iconView.animation?.let { !it.hasEnded() } == true
        if (iconView.tag == stateKey && (!isWriting || stillRunning)) return
        iconView.tag = stateKey

        iconView.clearAnimation()
        frameView?.clearAnimation()

        if (isWriting) {
            val dynamicColor = type.getDynamicColor()
            iconView.setImageResource(type.iconRes)
            iconView.imageTintList = ColorStateList.valueOf(dynamicColor)
            frameView?.background = ContextCompat.getDrawable(context, R.drawable.bg_work_icon_frame)

            val anim: Animation = when (type) {
                WorkActionType.FILE_CREATE -> {
                    AnimationSet(true).apply {
                        addAnimation(ScaleAnimation(
                            0.88f, 1.12f, 0.88f, 1.12f,
                            Animation.RELATIVE_TO_SELF, 0.5f,
                            Animation.RELATIVE_TO_SELF, 0.5f
                        ).apply {
                            duration = 750
                            repeatMode = Animation.REVERSE
                            repeatCount = Animation.INFINITE
                        })
                        addAnimation(AlphaAnimation(0.6f, 1.0f).apply {
                            duration = 750
                            repeatMode = Animation.REVERSE
                            repeatCount = Animation.INFINITE
                        })
                    }
                }
                WorkActionType.FILE_EDIT -> {
                    RotateAnimation(
                        -14f, 14f,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 450
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    }
                }
                WorkActionType.TERMINAL -> {
                    AlphaAnimation(0.25f, 1.0f).apply {
                        duration = 400
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    }
                }
                WorkActionType.WEB_SEARCH -> {
                    RotateAnimation(
                        0f, 360f,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 1800
                        interpolator = LinearInterpolator()
                        repeatCount = Animation.INFINITE
                    }
                }
                WorkActionType.FILE_READ -> {
                    TranslateAnimation(
                        Animation.RELATIVE_TO_SELF, -0.15f,
                        Animation.RELATIVE_TO_SELF, 0.15f,
                        Animation.RELATIVE_TO_SELF, 0f,
                        Animation.RELATIVE_TO_SELF, 0f
                    ).apply {
                        duration = 600
                        repeatMode = Animation.REVERSE
                        repeatCount = Animation.INFINITE
                    }
                }
                WorkActionType.CODE_SYNTHESIS -> {
                    AnimationSet(true).apply {
                        addAnimation(RotateAnimation(
                            0f, 360f,
                            Animation.RELATIVE_TO_SELF, 0.5f,
                            Animation.RELATIVE_TO_SELF, 0.5f
                        ).apply {
                            duration = 3200
                            interpolator = LinearInterpolator()
                            repeatCount = Animation.INFINITE
                        })
                        addAnimation(ScaleAnimation(
                            0.90f, 1.10f, 0.90f, 1.10f,
                            Animation.RELATIVE_TO_SELF, 0.5f,
                            Animation.RELATIVE_TO_SELF, 0.5f
                        ).apply {
                            duration = 800
                            repeatMode = Animation.REVERSE
                            repeatCount = Animation.INFINITE
                        })
                    }
                }
            }

            iconView.startAnimation(anim)

            val framePulse = AlphaAnimation(0.5f, 1.0f).apply {
                duration = 800
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            }
            frameView?.startAnimation(framePulse)

        } else {
            // Completed state
            val successColor = try { com.jarvis.assistant.util.ThemeManager.getThemeSuccessColor() } catch (_: Exception) { Color.parseColor("#00E676") }
            iconView.setImageResource(R.drawable.ic_work_check)
            iconView.imageTintList = ColorStateList.valueOf(successColor)
            frameView?.background = ContextCompat.getDrawable(context, R.drawable.bg_work_icon_frame_done)

            val popAnim = ScaleAnimation(
                0.5f, 1.0f, 0.5f, 1.0f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 350
                interpolator = OvershootInterpolator(2.2f)
            }
            iconView.startAnimation(popAnim)
        }
    }
}
