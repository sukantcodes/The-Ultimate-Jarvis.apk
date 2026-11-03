package com.jarvis.assistant.clock

import android.content.Context
import android.content.Intent
import com.jarvis.assistant.clock.voice.ClockDialog
import com.jarvis.assistant.clock.voice.ClockOps
import com.jarvis.assistant.clock.voice.ClockOutcome
import com.jarvis.assistant.clock.voice.Done
import com.jarvis.assistant.clock.voice.RingPort
import com.jarvis.assistant.ui.clock.ClockSettingsActivity
import java.time.ZonedDateTime

/** Reaches the ringing service. Stop and Snooze are requests to the service, which owns the sound. */
class AndroidRingPort(private val app: Context) : RingPort {
    override fun ringingId(): Long = RingState.ringingId
    override fun requestSnooze(id: Long) = AlarmRingService.send(app, AlarmRingService.ACTION_SNOOZE, id)
    override fun requestStop(id: Long) = AlarmRingService.send(app, AlarmRingService.ACTION_STOP, id)
}

/**
 * The AI and voice side of the alarm clock. It uses the same [ClockEngine] as the Clock screens (through [ClockOps]), so an
 * alarm made by voice shows up on the Alarms page at once and the reverse. Calls can wait up to a few seconds to read back the
 * result of Stop or Snooze, so they must not run on the main thread.
 */
object ClockAssistant {
    @Volatile private var ops: ClockOps? = null
    @Volatile private var dialog: ClockDialog? = null

    fun ops(context: Context): ClockOps = ops ?: synchronized(this) {
        ops ?: ClockOps(ClockServices.engine(context), AndroidRingPort(context.applicationContext), { ZonedDateTime.now() }).also { ops = it }
    }

    private fun dialog(context: Context): ClockDialog = dialog ?: synchronized(this) {
        dialog ?: ClockDialog(ops(context)).also { dialog = it }
    }

    /** Opens Settings > Clock. Used by the voice command and the AI tool. */
    fun openSettings(context: Context) {
        context.applicationContext.startActivity(Intent(context.applicationContext, ClockSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** True if a question about an alarm is waiting for the person's answer (so the next sentence goes here first). */
    fun hasPendingQuestion(context: Context): Boolean = dialog(context).hasPending

    /**
     * Handles a typed or spoken sentence if it belongs to the alarm clock, in any supported language. Returns the reply to say
     * or show, or null when the sentence is not about alarms.
     */
    fun handleText(context: Context, raw: String): String? {
        val app = context.applicationContext
        val reply = dialog(app).handle(raw) ?: return null
        val out = reply.outcome
        if (out is ClockOutcome.Success && out.kind == Done.OPEN_SETTINGS) openSettings(app)
        return ClockReplies.render(app, out, reply.language, ClockServices.engine(app).settings())
    }
}
