package com.jarvis.assistant.clock

import android.content.Context
import com.jarvis.assistant.R

/** Plain-language, translated explanations of why an alarm operation did not happen. Used by the Clock screens and the ring screen. */
object ClockMessages {

    fun forError(context: Context, error: ClockError): String = context.getString(
        when (error) {
            ClockError.INVALID_TIME -> R.string.clock_err_invalid_time
            ClockError.TIME_IN_PAST -> R.string.clock_err_time_in_past
            ClockError.NOT_FOUND -> R.string.clock_err_not_found
            ClockError.AMBIGUOUS -> R.string.clock_err_ambiguous
            ClockError.NEEDS_SELECTOR -> R.string.clock_err_needs_selector
            ClockError.NEEDS_AM_PM -> R.string.clock_err_needs_am_pm
            ClockError.EXACT_ALARM_DENIED -> R.string.clock_err_denied
            ClockError.SCHEDULE_FAILED -> R.string.clock_err_schedule_failed
            ClockError.VERIFY_FAILED -> R.string.clock_err_verify_failed
            ClockError.NOTHING_RINGING -> R.string.clock_err_nothing_ringing
            ClockError.SNOOZE_LIMIT -> R.string.clock_err_snooze_limit
        }
    )

    fun forFailure(context: Context, failure: ClockResult.Failed): String = forError(context, failure.error)
}
