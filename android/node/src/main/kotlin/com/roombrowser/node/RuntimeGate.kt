package com.roombrowser.node

import kotlinx.coroutines.sync.Mutex

/**
 * The process-wide one-call-at-a-time gate every [NodeRunner] call passes:
 * the lock that turns two would-be overlapping runtime calls into a queue,
 * with a CANCELLABLE wait.
 *
 * WHY A MUTEX AND NOT A REENTRANT LOCK. The runtime-kill protocol requires
 * exactly one call in flight (see [NodeRunner] for the whole reasoning), and
 * the old guard was a static `ReentrantLock` whose `lock()` is
 * UNINTERRUPTIBLE: a caller queued behind the call in flight was parked on
 * its thread until the holder left — a form dialog can hold one for
 * [NodeProtocol.REVERSE_USER_INPUT_TIMEOUT_MS] — and coroutine cancellation
 * (the chat's Stop button) could not touch it, so the waiter's turn looked
 * frozen until the dialog resolved or timed out. A coroutine [Mutex]
 * suspends the WAITER instead of parking it, so cancelling the waiter's
 * coroutine ends the wait at once. The HOLDER is unchanged: the call that
 * owns the gate runs blocking socket code with no suspension points in it,
 * so a cancellation can only land after the call has answered and the gate
 * has opened — exactly the strength the kill protocol demands, no stronger
 * and no weaker than the lock it replaces.
 *
 * NO RE-ENTRY, EVER — AND THAT IS NOW A LOAD-BEARING RULE. A ReentrantLock
 * silently tolerated the holder's own thread taking it again; a Mutex
 * DEADLOCKS on that. Nothing re-enters by construction: the only code that
 * runs while the gate is held is NodeClient's connect/exchange/close plus
 * the reverse handlers a call registered, and those answer the runtime over
 * HTTP (`agent_generate`, the chat's own model) or a form host's deferred
 * (`user_input`, the dialog's answer) — none of them starts another
 * NodeRunner call. A re-entrant call was never legal anyway: its connect
 * would name a socket the running runtime is not serving, which is the
 * command that ENDS the `:node` process the outer call is still talking to.
 * The tests beside this file pin the two properties that must never drift:
 * one call at a time no matter how many arrive together, and a cancelled
 * waiter leaves the queue without running and without a trace.
 *
 * KEEP-ALIVE IS DELIBERATELY ABSENT. The gate is only the WAIT; the
 * lifecycle around it still starts a runtime per call and closes it after
 * ("nothing is kept between calls"). Reusing a warm runtime across calls is
 * a separate change this task (8-f / WP5) explicitly deferred: it would
 * change what a wedged script means for every later call, and that decision
 * belongs to the lifecycle, not to the queue in front of it.
 */
internal class RuntimeGate {

    private val mutex = Mutex()

    /**
     * Runs [body] as the one call in flight, with [onContended] announced
     * first when another call was already running and had to be waited for.
     *
     * The notice is advisory and fires BEFORE the wait begins — the caller's
     * honest "another call is running, this one is queued", never a guess
     * about how long the wait will be. It runs under runCatching because a
     * status line that threw must not fail the call it was announcing; a
     * null hook (every caller that has no status to show) waits in silence,
     * exactly as before this gate existed.
     *
     * Cancellation while WAITING is the caller's to keep: it propagates out
     * of here having never acquired the gate, so no unlock is owed — the
     * acquire happens before the `try`. Cancellation of the HOLDER changes
     * nothing: [body] has no suspension points, so it runs to its answer and
     * the `finally` opens the gate for the next caller either way.
     */
    suspend fun <T> run(onContended: (() -> Unit)?, body: () -> T): T {
        if (!mutex.tryLock()) {
            runCatching { onContended?.invoke() }
            mutex.lock()
        }
        try {
            return body()
        } finally {
            mutex.unlock()
        }
    }
}
