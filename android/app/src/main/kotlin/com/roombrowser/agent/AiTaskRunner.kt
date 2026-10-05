package com.roombrowser.agent

import android.app.ActivityManager
import android.content.Context
import com.roombrowser.data.db.AiTaskEntity

/**
 * What one attempt to run a scheduled task produced. [Deferred] is a
 * first-class result: the occurrence happened, but nothing could execute it,
 * and the reason is stored so the UI can say so instead of showing a run that
 * never took place.
 */
sealed class AiTaskRunOutcome {
    data class Completed(val summary: String) : AiTaskRunOutcome()
    data class Deferred(val reason: String) : AiTaskRunOutcome()
    data class Failed(val message: String) : AiTaskRunOutcome()
}

/**
 * The seam between the scheduler and whatever can actually drive an agent
 * turn. The worker owns WHEN a task runs; this owns WHETHER it can, and the
 * answer is allowed to be "not from here" — the same interface carries the
 * eventual headless executor without the worker changing.
 */
fun interface AiTaskRunner {
    suspend fun run(task: AiTaskEntity): AiTaskRunOutcome
}

/**
 * The runner the WORKER uses: it never runs a turn and never pretends to.
 *
 * THE EVIDENCE. WorkManager here is pinned to the default process
 * (`RoomBrowserApp.workManagerConfiguration` sets
 * `setDefaultProcessName(packageName)`), so an [androidx.work.Worker] always
 * runs outside ':browser' — and ':browser' is where the engine lives. One
 * process may hold one runtime, over profile data both processes share, so the
 * worker must not start a second one.
 *
 * So the worker RECORDS and ':browser' RUNS: a Deferred outcome is a queue
 * entry (see [AiTaskDelivery], which watches exactly these rows and executes
 * them), and the reason it records is the wait, read from the OS rather than
 * guessed — the browser is up and will take it now, or it is not running and
 * the occurrence waits for the next time it is.
 */
class DeferredAiTaskRunner(private val context: Context) : AiTaskRunner {

    override suspend fun run(task: AiTaskEntity): AiTaskRunOutcome {
        val reason = if (browserProcessRunning()) {
            "Queued: the browser is open, and scheduled runs execute there."
        } else {
            "Queued: no browser process is running, so this waits for the next time " +
                "Room Browser is open."
        }
        return AiTaskRunOutcome.Deferred(reason)
    }

    /**
     * Whether ':browser' is a live process of this app. `getRunningAppProcesses`
     * is filtered to the caller's own UID on modern Android, which is exactly
     * the scope wanted here.
     */
    private fun browserProcessRunning(): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val target = context.packageName + ":browser"
        manager.runningAppProcesses?.any { it.processName == target } == true
    }.getOrDefault(false)
}

/**
 * One place to choose the WORKER's runner. The process that can actually run a
 * turn does not come through here: it is [AiTaskDelivery] in ':browser', which
 * picks up the rows this one leaves behind.
 */
object AiTaskRunners {
    fun forContext(context: Context): AiTaskRunner = DeferredAiTaskRunner(context)
}
