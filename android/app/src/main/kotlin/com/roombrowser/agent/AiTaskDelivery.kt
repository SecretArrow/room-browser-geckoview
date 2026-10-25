package com.roombrowser.agent

import android.content.Context
import com.roombrowser.data.repo.AiTaskRepository
import com.roombrowser.di.AppGraph
import com.roombrowser.domain.task.AiTaskRunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
 * into real runs. Room's multi-instance invalidation (enabled on the database
 * builder — see AppDatabase.build) is what carries the record across, which is
 * why this is a query and not an IPC channel.
 *
 * EVENT-DRIVEN, with one honest seam. The delivery used to be a <=20 s sweep —
 * a Room query for the process lifetime, even with an empty queue and the
 * browser backgrounded. Now the queue's Room Flow (the same query, observed)
 * IS the trigger: every DEFERRED row the worker (default process) or a run of
 * ours writes lands here within moments of the invalidation ping, and nothing
 * wakes the CPU in between. The trigger is a signal, not the payload — the
 * drain re-reads the queue when it runs — so a burst of emissions while a run
 * is in flight costs one cheap query afterwards, never a second concurrent
 * run.
 *
 * THE SEAM, and why a slow fallback survives at all: a run that CANNOT go
 * ahead (this process not bound to the task's profile yet, the agent switched
 * off) is left DEFERRED and writes NOTHING — that is what keeps a declined row
 * from re-triggering itself through the invalidation its own write would
 * cause. Which means the state that blocked it can later change (the browser
 * finishes binding the profile, the user re-enables the agent) with no
 * database write anywhere to wake the Flow. [FALLBACK_POLL_MS] re-looks at the
 * queue well past the old cadence — an event of once-a-minute magnitude, and
 * the only thing standing between a deferred row and its "the next time you
 * open Room Browser" promise. (The invalidation itself is enabled; this is
 * not a lost-ping guard, it is the no-write-at-all case.)
 *
 * A row that cannot run is LEFT in place, still deferred, so a later drain can
 * pick it up. Nothing here ever WRITES on a decline — that is what keeps a
 * declined row from re-triggering itself through the invalidation its own
 * write would cause.
 */
class AiTaskDelivery(
    private val repo: AiTaskRepository,
    private val runner: AiTaskRunner
) {

    /**
     * The one delivery loop. Three coroutines on the caller's scope: the Flow
     * and the fallback poll only SIGNAL, and the third drains — so runs are
     * strictly sequential (two tasks must not drive two sessions at once
     * through one engine, and a run that takes minutes may not be interrupted
     * by the next row to appear while it works), and at most one drain exists
     * at any time, which is the run-at-most-one promise kept without locks.
     */
    fun start(scope: CoroutineScope) {
        val wake = Channel<Unit>(Channel.UNLIMITED)
        // Every change to the DEFERRED queue wakes the drain. distinctUntil
        // Changed keeps a re-emission of the SAME rows (an unrelated write to
        // the table, a re-collect) from queuing a pointless signal.
        scope.launch {
            repo.observeDeferred().distinctUntilChanged().collect { tasks ->
                if (tasks.isNotEmpty()) wake.trySend(Unit)
            }
        }
        // The fallback: the queue that deferred ITSELF changes without a
        // write. Slow, on purpose — see the class comment.
        scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(FALLBACK_POLL_MS)
                wake.trySend(Unit)
            }
        }
        // One consumer: the signals are serialized here, each drain re-reads
        // the queue, and a drain that finds it empty is one indexed query.
        scope.launch {
            while (currentCoroutineContext().isActive) {
                // The signal carries nothing; the drain reads the queue.
                wake.receive()
                drainOnce()
            }
        }
    }

    /**
     * Runs every currently-deferred row once, in insertion order, and stops
     * when the queue is empty. Honest outcomes only: Completed and Failed are
     * written to the row; Deferred is left exactly as it is, because the
     * reason on the row already says why and rewriting it would re-emit the
     * very row this drain is reading.
     */
    private suspend fun drainOnce() {
        while (currentCoroutineContext().isActive) {
            val tasks = runCatching { repo.deferredNow() }.getOrDefault(emptyList())
            if (tasks.isEmpty()) return
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
                    // rewriting it would re-emit the very row this drain is
                    // reading.
                    is AiTaskRunOutcome.Deferred -> Unit
                }
            }
            // The loop re-reads: the runs above may have left NEW rows
            // deferred (a run that could not start after all), and a drain
            // that stopped here would wait out the fallback for work it could
            // do now.
        }
    }

    companion object {
        /**
         * How long a row that could not run waits before it is looked at
         * again WITHOUT a write to announce the change that unblocked it.
         * Sixty seconds against the old twenty: the Flow carries everything a
         * write announces, so this cadence only serves the no-write case, and
         * an always-on ticker is exactly what this class exists to have
         * retired.
         */
        private const val FALLBACK_POLL_MS = 60_000L

        /** The observer for the process that owns the engine. */
        fun forBrowserProcess(app: AppGraph, context: Context): AiTaskDelivery =
            AiTaskDelivery(
                repo = app.aiTaskRepo,
                runner = HeadlessAiTaskRunner(context, app)
            )
    }
}
