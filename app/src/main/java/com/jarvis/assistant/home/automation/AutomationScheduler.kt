package com.jarvis.assistant.home.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.assistant.home.DeviceAction
import com.jarvis.assistant.home.DeviceActionType
import com.jarvis.assistant.home.HomeServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Schedules routines with WorkManager, which Android keeps alive across restarts and Doze. Android may run a time-based
 * job some minutes late on a sleeping phone; JARVIS records the actual run time in the routine's history.
 */
object AutomationScheduler {
    private const val KEY_ID = "automation_id"
    private const val KEY_STEP = "from_step"
    private const val POLL_WORK = "home_state_poll"

    private fun timeWork(id: String) = "home_time_$id"

    /** Re-plans every enabled time routine and the state poller. Call after any change and at app start. */
    fun rescheduleAll(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        val all = HomeServices.automationStore(app).automations()
        all.forEach { a ->
            val next = if (a.enabled && a.isConfigured) AutomationTime.nextFire(a.trigger, now) else null
            if (next == null) wm.cancelUniqueWork(timeWork(a.id))
            else {
                val req = OneTimeWorkRequestBuilder<AutomationWorker>()
                    .setInitialDelay(Duration.between(now, next).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putString(KEY_ID, a.id).putInt(KEY_STEP, 0).build()).build()
                wm.enqueueUniqueWork(timeWork(a.id), ExistingWorkPolicy.REPLACE, req)
            }
        }
        if (all.any { it.enabled && it.isConfigured && it.trigger is Trigger.DeviceState }) {
            val poll = PeriodicWorkRequestBuilder<StatePollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().build()).build()
            wm.enqueueUniquePeriodicWork(POLL_WORK, ExistingPeriodicWorkPolicy.UPDATE, poll)
        } else wm.cancelUniqueWork(POLL_WORK)
    }

    /** Resumes a routine from [fromStep] after a delay longer than the engine waits in place. */
    fun scheduleContinuation(context: Context, id: String, fromStep: Int, delaySeconds: Int) {
        val req = OneTimeWorkRequestBuilder<ContinuationWorker>().setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(KEY_ID, id).putInt(KEY_STEP, fromStep).build()).build()
        WorkManager.getInstance(context.applicationContext).enqueue(req)
    }

    class AutomationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
            val engine = HomeServices.engine(applicationContext)
            val store = HomeServices.automationStore(applicationContext)
            val a = store.get(id)
            if (a != null && a.enabled) {
                engine.run(id)
                // A one-time schedule pauses itself after it has run, so it never fires twice.
                val t = a.trigger
                if (t is Trigger.Time && !t.repeat) store.save(a.copy(enabled = false))
            }
            rescheduleAll(applicationContext)
            Result.success()
        }
    }

    class ContinuationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
            HomeServices.engine(applicationContext).run(id, inputData.getInt(KEY_STEP, 0), skipFirstDelay = true)
            Result.success()
        }
    }

    /** Re-reads the state of devices used by state triggers so the engine can notice real changes. */
    class StatePollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val app = applicationContext
            val controller = HomeServices.controller(app)
            HomeServices.warm(app)
            HomeServices.automationStore(app).automations()
                .filter { it.enabled && it.isConfigured }.mapNotNull { (it.trigger as? Trigger.DeviceState)?.deviceId }.distinct()
                .forEach { controller.run(it, DeviceAction(DeviceActionType.REFRESH)) }
            Result.success()
        }
    }
}
