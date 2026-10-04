package com.roombrowser

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.roombrowser.browser.engine.ProfileEngine
import com.roombrowser.di.AppGraph
import com.roombrowser.work.RetentionCleanupWorker
import java.util.concurrent.TimeUnit

/**
 * MULTI-PROCESS NOTE: this class is the application for BOTH processes —
 * the default one and `:browser` (see the manifest). [onCreate] therefore
 * runs twice, and each process builds its own [AppGraph] on purpose
 * (per-process WebView data dir, wallet and vault sessions).
 *
 * WorkManager is the exception: it must have ONE owner. Without
 * [Configuration.Builder.setDefaultProcessName] every process gets its own
 * `WorkManagerImpl` driving the same shared `androidx.work.workdb`, and the
 * retention purge could then execute inside `:browser` — the process
 * ProfileSwitchExecutor kills outright on a profile switch, which would
 * tear down a write transaction mid-flight. The scheduling call is gated
 * on the same check so only the owning process enqueues.
 */
class RoomBrowserApp : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // ProfileEngine keeps the once-per-process binding for the whole app;
        // it needs the application context to reach the engine facade's
        // `bind`, and this is the first moment one exists.
        ProfileEngine.init(this)
        graph = AppGraph(this)
        if (isDefaultProcess()) scheduleRetentionCleanup()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName(packageName)
            .build()

    /** True in the process that owns WorkManager (the default one). */
    private fun isDefaultProcess(): Boolean =
        Application.getProcessName() == packageName

    /**
     * Battery-friendly daily cleanup: purge expired IP-history records and
     * stale closed-tab tombstones. No network, no polling (spec section 50).
     *
     * UPDATE, not KEEP: KEEP pins the request as first enqueued, so every
     * later change to the period or the constraints would be silently
     * dropped on every existing install. The purge is a write-heavy Room
     * transaction, hence `requiresBatteryNotLow` — no network constraint,
     * because the work is entirely local.
     */
    private fun scheduleRetentionCleanup() {
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "retention-cleanup",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RetentionCleanupWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    )
                    .build()
            )
        }
    }
}
