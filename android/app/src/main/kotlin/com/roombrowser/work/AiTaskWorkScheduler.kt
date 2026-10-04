package com.roombrowser.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.roombrowser.data.db.AiTaskEntity
import com.roombrowser.data.repo.schedule
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Enqueues (and cancels) the one pending delivery per task.
 *
 * Every task owns exactly one unique work name, so saving an edit, toggling a
 * task off, or the worker's own reschedule all replace the same slot instead
 * of stacking a delivery per save. A schedule that can never fire enqueues
 * nothing and the existing slot is cancelled: the alternative — silently
 * scheduling some default — would run something the user never asked for.
 */
object AiTaskWorkScheduler {

    /** The task's next delivery, or cancels when there is none. A null [task]
     *  (deleted while a worker was enqueued) cancels too. */
    fun scheduleNext(context: Context, task: AiTaskEntity?) {
        if (task == null) return
        if (!task.enabled) {
            cancel(context, task.id)
            return
        }
        val delayMs = AiTaskSchedulePlanner.delayMs(
            task.schedule,
            System.currentTimeMillis(),
            ZoneId.systemDefault()
        )
        if (delayMs == null) {
            cancel(context, task.id)
            return
        }
        runCatching {
            val request = OneTimeWorkRequestBuilder<AiTaskWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(AiTaskWorker.KEY_TASK_ID to task.id))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                AiTaskSchedulePlanner.uniqueWorkName(task.id),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun cancel(context: Context, taskId: Long) {
        runCatching {
            WorkManager.getInstance(context)
                .cancelUniqueWork(AiTaskSchedulePlanner.uniqueWorkName(taskId))
        }
    }
}
