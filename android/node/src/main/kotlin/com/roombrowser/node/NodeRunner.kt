package com.roombrowser.node

import android.content.Context
import android.os.SystemClock
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.io.InterruptedIOException

/**
 * The app side of the bridge: runs one script — or one plugin event — in the
 * embedded runtime, one call at a time.
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
 * (Reusing a warm runtime across calls was considered with this change and
 * deliberately deferred — task 8-f / WP5 kept this to the WAIT, not the
 * lifecycle, because a shared runtime changes what a wedged script means for
 * every call after it. See [RuntimeGate].)
 *
 * THE WAIT IS CANCELLABLE. The one-call-at-a-time guard is a coroutine mutex
 * ([RuntimeGate]) rather than the uninterruptible `ReentrantLock` it replaces:
 * a caller queued behind the call in flight is SUSPENDED, not parked on a
 * thread, so the chat's Stop button ends the wait the moment it lands — where
 * before, a waiter behind a call whose form dialog was open (up to
 * [NodeProtocol.REVERSE_USER_INPUT_TIMEOUT_MS]) could neither run nor stop.
 * The price of that is one new clause in the never-throws contracts below: a
 * caller CANCELLED WHILE WAITING is answered with the cancellation itself
 * instead of an answer, because swallowing it would run a stopped turn's
 * coroutine on as though the plugin had failed. The call that already HOLDS
 * the gate is exactly as uninterruptible as it ever was: it runs blocking
 * socket code with no suspension points, answers, and opens the gate after.
 */
class NodeRunner(
    context: Context,
    /**
     * Called once, before the wait, when this call found another one in
     * flight — the seam a caller wires its status line to (the app side
     * spells its words `RUNTIME_CONTENDED_NOTICE`). Null (every caller that
     * has no status to show) waits in silence, exactly as before. Advisory:
     * [RuntimeGate.run] runs it under runCatching, so a hook that throws
     * cannot fail the call it was announcing.
     */
    private val onContended: (() -> Unit)? = null
) {

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
     *
     * The one exception to never-throws is cancellation while WAITING for the
     * gate (see the class doc): a caller whose coroutine is stopped behind
     * another call is answered with the cancellation itself, so a stopped turn
     * actually stops.
     */
    suspend fun run(
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
        return gate.run(onContended) { runOnce(script, userAgent, timeoutMillis) }
    }

    /**
     * Runs one plugin event under the SAME process-wide lock and the same
     * start/connect/close lifecycle as [run] — one call at a time, a runtime
     * started for the call and torn down after it.
     *
     * [onReverse] is passed straight through to [NodeClient.plugin]: the
     * blocking handler that answers `room.agent.generate` while the plugin
     * runs, or null when this call has none — in which case the runtime's
     * own ask is answered with "agent generation is not available" rather
     * than ignored, and the plugin call carries on.
     *
     * Never throws, for the same reasons [run] never throws: the runtime
     * being absent, failing to start, dying mid-plugin, or refusing a request
     * is a FAILED RESULT carrying the words, because a plugin that could not
     * run is an answer the chat has to read (and the turn has to carry on
     * past), not an exception the caller has to word itself. A handler that
     * throws is not this method's problem either — the reverse answer turns
     * it into the reverse line's own error envelope before it can get here.
     * The one exception, as on [run]: cancellation while WAITING for the
     * gate travels to the caller instead of an answer.
     */
    suspend fun plugin(
        call: PluginCallRequest,
        onReverse: ((JsonObject) -> JsonObject)? = null
    ): PluginCallResult = plugin(
        call = call,
        reverse = onReverse?.let { handler ->
            ReverseHandlers().register(NodeProtocol.REVERSE_GENERATE, handler)
        }
    )

    /**
     * Runs one plugin event with the reverse commands answered by a
     * [ReverseHandlers] registry — `agent_generate` for generation, and
     * `user_input` for the runtime form, each answered by whoever registered
     * it, each defaulting to its own refusal when nobody did. The module
     * fields of [call] ([PluginCallRequest.format] and its siblings) ride to
     * the wire unchanged, so a zagents-format row runs under the module SDK
     * with its own prompt, budget, state root and stored form answers.
     *
     * Never throws either, for the same reasons as the shorthand above —
     * with the same one exception: cancellation while WAITING for the gate
     * travels to the caller instead of an answer (see the class doc).
     */
    suspend fun plugin(
        call: PluginCallRequest,
        reverse: ReverseHandlers?
    ): PluginCallResult {
        if (!NodeRuntime.available) {
            return PluginCallResult(
                ok = false,
                output = "this build carries no Node runtime for this device's CPU"
            )
        }
        return gate.run(onContended) { pluginOnce(call, reverse) }
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

    private fun pluginOnce(
        call: PluginCallRequest,
        reverse: ReverseHandlers?
    ): PluginCallResult {
        val startedAt = SystemClock.elapsedRealtime()
        val client = try {
            NodeClient.connect(appContext)
        } catch (err: IOException) {
            return PluginCallResult(
                ok = false,
                output = err.message ?: "the Node runtime did not start"
            )
        }
        return try {
            client.plugin(call, reverse)
        } catch (err: InterruptedIOException) {
            PluginCallResult(
                ok = false,
                output = "the Node runtime did not answer within " +
                    "${NodeClient.readTimeoutFor(call.timeoutMs) / 1000}s, so it was shut down"
            )
        } catch (err: IOException) {
            PluginCallResult(
                ok = false,
                output = "the Node runtime stopped before it answered: " +
                    (err.message ?: "no reason given")
            )
        } catch (err: IllegalStateException) {
            // The runtime's own refusal (an unknown command, a bad request):
            // its words are the most useful thing this side can say.
            PluginCallResult(ok = false, output = err.message ?: "the Node runtime refused the request")
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

        /**
         * THE process-wide gate: the runtime is one process shared by every
         * chat in the app, so "one call at a time" is an app-wide promise,
         * not a per-runner one. The gate owns the wait's semantics (see
         * [RuntimeGate]); this is only where its one instance lives.
         */
        private val gate = RuntimeGate()
    }
}
