package com.roombrowser.agent

import android.content.Context
import com.roombrowser.data.repo.AiTaskRepository
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.task.AiTaskRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the occurrences the scheduler recorded but could not execute, in the
 * one process that is allowed to start the engine.
 *
 * THE SPLIT, and why it is a split at all: WorkManager is pinned to the default
 * process (`RoomBrowserApp.workManagerConfiguration`), so the worker can decide
 * that a task is due and can do nothing else — the engine lives in ':browser',
 * and one process may hold one runtime over the profile data both processes
 * share. So the worker RECORDS (a DEFERRED row, with the reason) and this
 * object, alive in ':browser' for as long as that process is, turns those rows
 * into real runs. Room's multi-instance invalidation is what carries the record
 * across, which is why this is a query and not an IPC channel.
 *
 * WHY IT SWEEPS rather than only observing. Observing alone is not enough to
 * keep the promise a deferred row makes ("this runs the next time you open Room
 * Browser"). This object starts in Application.onCreate, which runs BEFORE the
 * browser binds the process to a profile — so the first look at the queue
 * happens while the answer is still "not this profile", and by the time it is
 * the right profile nothing writes to the row, so no observer is woken. The
 * queue is therefore re-read both when it changes and once per [RETRY_MS],
 * which is what turns "the next time you open the browser" into something that
 * happens seconds after it opens instead of at the task's next occurrence.
 *
 * A row that cannot run (the user is browsing a different profile, the agent is
 * switched off) is LEFT in place, still deferred, so a later sweep can pick it
 * up. Nothing here ever WRITES on a decline — that is what keeps a declined row
 * from re-triggering itself through the invalidation its own write would cause.
 */
class AiTaskDelivery(
    private val repo: AiTaskRepository,
    private val runner: AiTaskRunner
) {

    /** Sequential on purpose: two tasks must not drive two sessions at once
     *  through one engine, and a run that takes minutes may not be interrupted
     *  by the next row to appear while it works. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (currentCoroutineContext().isActive) {
                val tasks = repo.deferredNow()
                for (task in tasks) {
                    val outcome = runCatching { runner.run(task) }.getOrElse { error ->
                        AiTaskRunOutcome.Failed(error.message ?: error.javaClass.simpleName)
                    }
                    val at = System.currentTimeMillis()
                    when (outcome) {
                        is AiTaskRunOutcome.Completed ->
                            repo.recordRun(task.id, at, AiTaskRunStatus.COMPLETED.name, outcome.summary)
                        is AiTaskRunOutcome.Failed ->
                            repo.recordRun(task.id, at, AiTaskRunStatus.FAILED.name, outcome.message)
                        // Unchanged: the reason on the row already says why, and
                        // rewriting it would re-emit the very row this sweep is
                        // reading.
                        is AiTaskRunOutcome.Deferred -> Unit
                    }
                }
                // Wake early when the queue changes; otherwise look again after
                // the retry window, because the state that blocked a row (this
                // process not being bound to its profile yet) changes without
                // any write to wake an observer.
                withTimeoutOrNull(RETRY_MS) {
                    repo.observeDeferred().first { it != tasks }
                }
            }
        }
    }

    companion object {
        /** How long a row that could not run waits before it is looked at again. */
        private const val RETRY_MS = 20_000L

        /** The observer for the process that owns the engine. */
        fun forBrowserProcess(app: AppGraph, context: Context): AiTaskDelivery =
            AiTaskDelivery(
                repo = app.aiTaskRepo,
                runner = HeadlessAiTaskRunner(context, app)
            )
    }
}
