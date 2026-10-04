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
 * Today's only runner: it never invents an execution path, and records why.
 *
 * THE EVIDENCE. WorkManager here is pinned to the default process
 * (`RoomBrowserApp.workManagerConfiguration` sets
 * `setDefaultProcessName(packageName)`), so an [androidx.work.Worker] always
 * runs outside ':browser'. The agent stack lives in ':browser' and only there:
 * `BrowserActivity` is declared `android:process=":browser"`, and it is a
 * `BrowserViewModel` — created by that Activity — that `BrowserAgentController`
 * needs to act on a page. The headless-session spike (`spike/headless-session`)
 * exists precisely because a GeckoView session without an attached view is
 * the open question; until it is answered and wired, no honest worker can run
 * a turn.
 *
 * So this runner reports Deferred, distinguishing the two real cases so the
 * recorded reason is true rather than generic: the browser is open (the stack
 * exists, but is unreachable across the process boundary) versus it is not
 * running at all. The process probe is a fact read from the OS, not a guess.
 */
class DeferredAiTaskRunner(private val context: Context) : AiTaskRunner {

    override suspend fun run(task: AiTaskEntity): AiTaskRunOutcome {
        val reason = if (browserProcessRunning()) {
            "Deferred: the browser is open, but a background worker cannot drive a turn " +
                "across the process boundary yet."
        } else {
            "Deferred: the browser process and its agent stack are not running."
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
 * One place to choose the runner implementation. When the headless answer is
 * proven, a `HeadlessSessionTaskRunner` slots in on the `forContext` line and
 * nothing else — worker, scheduler, UI — changes.
 */
object AiTaskRunners {
    fun forContext(context: Context): AiTaskRunner = DeferredAiTaskRunner(context)
}
