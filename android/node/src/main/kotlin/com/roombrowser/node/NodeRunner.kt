package com.roombrowser.node

import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock

/**
 * The app side of the bridge: runs one script in the embedded runtime, one call
 * at a time.
 *
 * WHY THE LOCK. Every call asks for the runtime under a name of its own, and a
 * command naming a socket the running runtime is not serving is answered by
 * ending the process that holds it (see [NodeService]) — so two calls that
 * overlapped would have the second one kill the runtime the first is still
 * talking to. The lock is what turns that into a queue, and it is process-wide
 * because the runtime is one process shared by every chat in the app.
 *
 * NOTHING IS KEPT BETWEEN CALLS. The runtime is started for the call and exits
 * when the connection closes, so a chat that never runs a script never pays for
 * a runtime, and a script that wedged one cannot be inherited by the next call.
 */
class NodeRunner(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Runs [script] as the body of an async function and returns what it
     * produced.
     *
     * Never throws for the script's own failure — a script that throws is an
     * answer the model has to read, not an exception the caller has to handle —
     * and never throws for the runtime being absent, which is reported in the
     * same shape. [userAgent] is the identity every request the script makes
     * carries; null is a runtime that refuses to make one at all.
     */
    fun run(
        script: String,
        userAgent: String?,
        timeoutMillis: Long = DEFAULT_BUDGET_MS
    ): NodeRunResult {
        if (!NodeRuntime.available) {
            return NodeRunResult(
                ok = false,
                output = "this build carries no Node runtime for this device's CPU",
                ms = 0
            )
        }
        lock.lock()
        try {
            return runOnce(script, userAgent, timeoutMillis)
        } finally {
            lock.unlock()
        }
    }

    private fun runOnce(script: String, userAgent: String?, timeoutMillis: Long): NodeRunResult {
        val startedAt = SystemClock.elapsedRealtime()
        val client = try {
            NodeClient.connect(appContext)
        } catch (err: IOException) {
            return NodeRunResult(
                ok = false,
                output = err.message ?: "the Node runtime did not start",
                ms = elapsed(startedAt)
            )
        }
        return try {
            client.run(script, userAgent, timeoutMillis)
        } catch (err: InterruptedIOException) {
            // The socket gave up on a runtime that never answered, which is
            // either a script the runtime's own timer could not stop or a
            // runtime that died mid-script. Closing the socket ends it either
            // way, so the next call starts a fresh one.
            NodeRunResult(
                ok = false,
                output = "the Node runtime did not answer within " +
                    "${NodeClient.readTimeoutFor(timeoutMillis) / 1000}s, so it was shut down",
                ms = elapsed(startedAt)
            )
        } catch (err: IOException) {
            NodeRunResult(
                ok = false,
                output = "the Node runtime stopped before it answered: " +
                    (err.message ?: "no reason given"),
                ms = elapsed(startedAt)
            )
        } finally {
            client.close()
        }
    }

    private fun elapsed(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

    companion object {

        /**
         * A script's own budget, in milliseconds. Long enough for a handful of
         * network calls, short enough that a step cannot look like a hang.
         */
        const val DEFAULT_BUDGET_MS = 30_000L

        private val lock = ReentrantLock()
    }
}
