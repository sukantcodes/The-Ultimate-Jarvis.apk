package com.jarvis.assistant.ui.clock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.clock.AlarmRingService
import com.jarvis.assistant.clock.AlarmScheduler
import com.jarvis.assistant.clock.ClockFormat
import com.jarvis.assistant.clock.ClockServices
import com.jarvis.assistant.clock.RingState
import com.jarvis.assistant.clock.VolumeKeyAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The JARVIS alarm screen. It only shows what [AlarmRingService] is doing and sends it Stop or Snooze. The sound and
 * vibration belong to the service, so the alarm keeps ringing if this screen is closed, and it closes by itself when the
 * ringing ends (from here, the notification, the AI or the silence timeout).
 */
class AlarmRingActivity : AppCompatActivity() {

    private val engine by lazy { ClockServices.engine(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timeView: TextView
    private lateinit var labelView: TextView
    private lateinit var dateView: TextView
    private lateinit var noticeView: TextView
    private val onRingChanged: () -> Unit = { handler.post { refresh() } }
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* An alarm is ended with Stop or Snooze, not by backing out. */ }
        })
        startedAt = System.currentTimeMillis()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refresh()
    }

    override fun onStart() {
        super.onStart()
        RingState.addListener(onRingChanged)
        refresh()
    }

    override fun onStop() {
        RingState.removeListener(onRingChanged)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun refresh() {
        if (isFinishing) return
        if (!RingState.isRinging) {
            // The service sets its state a moment after the screen opens, so wait briefly before deciding nothing is ringing.
            if (System.currentTimeMillis() - startedAt < 2_500L) {
                handler.postDelayed({ refresh() }, 600L)
            } else {
                finish()
            }
            return
        }
        val alarm = engine.alarm(RingState.ringingId)
        if (alarm == null) { finish(); return }
        val settings = engine.settings()
        timeView.text = ClockFormat.time(this, alarm.hour, alarm.minute, settings)
        labelView.text = ClockFormat.title(this, alarm)
        dateView.visibility = if (settings.showRingScreenDate) android.view.View.VISIBLE else android.view.View.GONE
        dateView.text = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
        val notice = RingState.notice
        noticeView.text = notice ?: ""
        noticeView.visibility = if (notice.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun send(action: String) {
        AlarmRingService.send(this, action, RingState.ringingId)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (engine.settings().volumeKeyAction) {
                VolumeKeyAction.SNOOZE -> { if (event.repeatCount == 0) send(AlarmRingService.ACTION_SNOOZE); return true }
                VolumeKeyAction.DISMISS -> { if (event.repeatCount == 0) send(AlarmRingService.ACTION_STOP); return true }
                VolumeKeyAction.NOTHING -> return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- Layout ---------------------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#070914"))
            setPadding(dp(24), dp(72), dp(24), dp(48))
        }
        dateView = label(16f, "#9AA4B2")
        timeView = label(72f, "#FFFFFF", bold = true).apply { setPadding(0, dp(12), 0, 0) }
        labelView = label(22f, "#E6ECF5").apply { setPadding(0, dp(8), 0, 0) }
        noticeView = label(14f, "#FFB4A9").apply { setPadding(0, dp(16), 0, 0); visibility = android.view.View.GONE }
        root.addView(dateView)
        root.addView(timeView)
        root.addView(labelView)
        root.addView(noticeView)
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val snooze = bigButton(getString(R.string.clock_snooze), "#2A3350") { send(AlarmRingService.ACTION_SNOOZE) }
        val stop = bigButton(getString(R.string.clock_stop), "#E5484D") { send(AlarmRingService.ACTION_STOP) }
        root.addView(snooze)
        root.addView(stop)
        setContentView(root)
    }

    private fun label(sizeSp: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.create("sans-serif-light", Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun bigButton(label: String, color: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { cornerRadius = dp(36).toFloat(); setColor(Color.parseColor(color)) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(16) }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
