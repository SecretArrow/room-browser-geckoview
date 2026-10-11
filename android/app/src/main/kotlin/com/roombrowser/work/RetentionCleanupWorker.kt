package com.roombrowser.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.roombrowser.RoomBrowserApp

/**
 * Daily maintenance worker: applies the IP-history retention policy and
 * prunes closed-tab tombstones older than 30 days. Fully local — no
 * network requests, no polling.
 */
class RetentionCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val app = applicationContext as? RoomBrowserApp
                ?: return Result.success()
            val graph = app.graph
            val global = graph.appState.globalSettingsSnapshot()
            val cutoff = global.retention.cutoff(System.currentTimeMillis())
            if (cutoff != null) {
                graph.browserRepo.purgeIpHistory(cutoff)
            } else {
                // FOREVER retention: nothing to purge
            }
            graph.browserRepo.purgeOldClosedTabs(days = 30)
            Result.success()
        }.getOrElse {
            // A transient failure (SQLite busy, a profile DB being swapped
            // under a profile switch) deserves the backoff. A PERMANENT one
            // — a corrupt database, a schema mismatch — does not: retrying
            // it forever means this worker wakes the device every backoff
            // interval for the life of the install and never succeeds. Give
            // up after MAX_ATTEMPTS and let tomorrow's periodic run try a
            // fresh attempt chain.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    private companion object {
        /** Retries per periodic run before the attempt chain is abandoned. */
        const val MAX_ATTEMPTS = 3
    }
}
