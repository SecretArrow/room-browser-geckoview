package com.roombrowser.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.roombrowser.RoomBrowserApp
import com.roombrowser.agent.AiTaskRunOutcome
import com.roombrowser.data.repo.schedule
import com.roombrowser.domain.task.AiTaskRunStatus
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * One delivery of one scheduled AI task.
 *
 * The worker owns the DECISION (is an occurrence owed?) and the BOOKKEEPING
 * (stamp it, then schedule the next); whether a turn can actually run is the
 * [com.roombrowser.agent.AiTaskRunner] seam's to answer. A delivery that
 * cannot run is still stamped, with a DEFERRED status and its reason, so
 * catch-up fires exactly once instead of a missed occurrence being re-owed on
 * every later delivery.
 *
 * The row is re-read before rescheduling, so an edit made while this delivery
 * was in flight takes effect immediately, and a task deleted or disabled in
 * that window simply stops.
 */
class AiTaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        return runCatching { runDelivery(taskId) }.getOrElse { error ->
            // Cancellation is WorkManager stopping this delivery, not a fault.
            if (error is CancellationException) throw error
            // Same policy as RetentionCleanupWorker: a transient fault (SQLite
            // busy, a profile database swapped mid-flight) deserves backoff; a
            // permanent one must not wake the device forever.
            if (runAttemptCount < MAX_ATTEMPTS) return@getOrElse Result.retry()
            // Abandoning the chain still owes the task its next delivery.
            // Nothing else in the app re-enqueues one, so without this a task
            // that failed MAX_ATTEMPTS deliveries would stop, silently and for
            // good, until the user happened to edit it.
            reschedule(taskId)
            Result.failure()
        }
    }

    /** Best-effort: a delivery that could not even read its own row leaves the
     *  slot as it is rather than cancelling work it cannot identify. */
    private suspend fun reschedule(taskId: Long) {
        val app = applicationContext as? RoomBrowserApp ?: return
        runCatching {
            AiTaskWorkScheduler.scheduleNext(applicationContext, app.graph.aiTaskRepo.get(taskId))
        }
    }

    private suspend fun runDelivery(taskId: Long): Result {
        if (taskId <= 0L) return Result.success()
        val app = applicationContext as? RoomBrowserApp ?: return Result.success()
        val repo = app.graph.aiTaskRepo

        val task = repo.get(taskId) ?: return Result.success()
        if (!task.enabled) return Result.success()

        val now = System.currentTimeMillis()
        if (AiTaskSchedulePlanner.isDue(task.schedule, task.lastRunAtMs, now, ZoneId.systemDefault())) {
            if (AiTaskSchedulePlanner.requiresForeground(task.schedule)) {
                // Sub-floor cadence: take the foreground path where the system
                // allows it. A refusal is not fatal — the delivery still runs
                // (and today still defers), so swallow and continue.
                runCatching { setForeground(foregroundInfo(task.name)) }
            }
            val outcome = try {
                app.graph.aiTaskRunner.run(task)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AiTaskRunOutcome.Failed(t.message ?: t.javaClass.simpleName)
            }
            val (status, summary) = when (outcome) {
                is AiTaskRunOutcome.Completed -> AiTaskRunStatus.COMPLETED to outcome.summary
                is AiTaskRunOutcome.Deferred -> AiTaskRunStatus.DEFERRED to outcome.reason
                is AiTaskRunOutcome.Failed -> AiTaskRunStatus.FAILED to outcome.message
            }
            repo.recordRun(taskId, now, status.name, summary)
        }

        // Re-read: the row may have been edited, disabled or deleted during
        // the attempt above.
        AiTaskWorkScheduler.scheduleNext(applicationContext, repo.get(taskId))
        return Result.success()
    }

    private fun foregroundInfo(name: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Scheduled AI tasks", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Room Agent task")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID = "ai_task_id"

        /** Retries per delivery before the attempt chain is abandoned. */
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "ai_task_schedule"
        private const val NOTIFICATION_ID = 403
    }
}
